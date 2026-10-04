// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.payments

import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.export.AccountPurging
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.PAYMENTS
import com.honestrobin.time.db.tables.records.IntegrationsRecord
import com.honestrobin.time.invoicing.InvoiceService
import com.honestrobin.time.platform.Disconnected
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.crypto.SecretBox
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.OAuthStates
import com.honestrobin.time.platform.web.BadRequestException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class PaymentsStatus(
    val connected: Boolean,
    val mode: String?,
    val accountName: String?,
    val externalAccountId: String?,
    /** Stripe Connect is configured on this instance, so "Connect with Stripe" is offered. */
    val connectAvailable: Boolean,
    /** Where Stripe must send events when connected with your own key. */
    val webhookUrl: String?,
    val lastError: String?,
    /** Connected with Stripe's test keys: only test cards work, and no money moves. */
    val testMode: Boolean,
)

data class StripeKeyInput(val secretKey: String = "", val webhookSecret: String = "")

data class CheckoutStart(val url: String)

/** Connecting Stripe and taking payments on the public invoice page (spec §5.6, AT-3.4). */
@Service
class OnlinePaymentService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val stripe: StripeClient,
    private val settings: StripeSettings,
    private val secrets: SecretBox,
    private val invoices: InvoiceService,
    private val props: HonestRobinProperties,
    private val json: ObjectMapper,
    private val funnel: Funnel,
    private val events: org.springframework.context.ApplicationEventPublisher,
    private val oauth: OAuthStates,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun integration() = dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.eq("stripe")).fetchOne()

    private fun credentials(r: IntegrationsRecord): Map<String, String> =
        r.credentialsEncrypted?.let { json.readValue(secrets.decrypt(it), Map::class.java).entries.associate { (k, v) -> k.toString() to v.toString() } }.orEmpty()

    /** Whether the connection takes real money; Stripe's test keys (sk_test_, rk_test_) take only test cards. */
    private fun livemode(r: IntegrationsRecord): Boolean {
        val key = if (r.mode == "connect") settings.platformSecretKey else credentials(r)["secret_key"].orEmpty()
        return !key.startsWith("sk_test_") && !key.startsWith("rk_test_")
    }

    fun status(m: Member): PaymentsStatus {
        invoices.requireAccess(m)
        return tx.run {
            val r = integration()
            PaymentsStatus(
                connected = r?.status == "connected", mode = r?.mode, accountName = r?.displayName, externalAccountId = r?.externalAccountId,
                connectAvailable = settings.connectEnabled,
                webhookUrl = if (r?.mode == "api_key") "${props.baseUrl}/webhooks/stripe/${m.accountId}" else null,
                lastError = r?.lastError,
                testMode = r?.status == "connected" && !livemode(r),
            )
        }
    }

    fun connectWithKey(m: Member, input: StripeKeyInput): PaymentsStatus {

        m.requireWritable()
        m.requireAdmin()
        val key = input.secretKey.trim()
        val errors = mutableMapOf<String, String>()
        if (!key.startsWith("sk_") && !key.startsWith("rk_")) errors["secret_key"] = "Paste a Stripe secret key (sk_…) or restricted key (rk_…)"
        if (!input.webhookSecret.trim().startsWith("whsec_")) errors["webhook_secret"] = "Paste the signing secret of the webhook endpoint (whsec_…)"
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val account = try {
            stripe.account(key)
        } catch (e: StripeException) {
            throw ValidationException(mapOf("secret_key" to "Stripe did not accept this key: ${e.message}"))
        }
        val name = account["business_profile"]?.get("name")?.takeIf { !it.isNull }?.asText() ?: account["settings"]?.get("dashboard")?.get("display_name")?.takeIf { !it.isNull }?.asText()
        save(m, "api_key", account["id"].asText(), name, mapOf("secret_key" to key, "webhook_secret" to input.webhookSecret.trim()))
        return status(m)
    }

    /** The Stripe page that asks the account owner to authorise Honest Robin (Connect). */
    fun connectUrl(m: Member): String {
        m.requireWritable()
        m.requireAdmin()
        if (!settings.connectEnabled) throw ConflictException("connect_unavailable", "Stripe Connect is not set up on this instance. Connect with your own key instead.")
        val (state, pending) = oauth.start(m)
        tx.run {
            val r = integration() ?: dsl.newRecord(INTEGRATIONS).apply { accountId = m.accountId; kind = "stripe"; mode = "connect"; status = "error" }
            r.settings = JSONB.valueOf(json.writeValueAsString(mapOf(OAuthStates.KEY to pending)))
            r.store()
        }
        return "${settings.connectBaseUrl}/oauth/authorize?response_type=code&scope=read_write&client_id=${settings.connectClientId}" +
            "&state=$state&redirect_uri=${java.net.URLEncoder.encode("${props.baseUrl}/api/v1/public/stripe/callback", Charsets.UTF_8)}"
    }

    /**
     * Stripe sends the owner back here after they authorise; returns where to send the browser.
     * Only the browser session that started connecting, still an admin, can finish it.
     */
    fun connectCallback(state: String, code: String?, error: String?): String {
        val (account, token) = oauth.parse(state) ?: return "${props.baseUrl}/settings/payments?stripe=error"
        return DbContext.forAccount(account) {
            val r = tx.run { integration() } ?: return@forAccount "${props.baseUrl}/settings/payments?stripe=error"
            val pending = json.readValue(r.settings.data(), Map::class.java)[OAuthStates.KEY]
            if (!oauth.matches(pending, account, token) || code == null || error != null) return@forAccount "${props.baseUrl}/settings/payments?stripe=error"
            val stripeAccount = try {
                stripe.oauthToken(code)
            } catch (e: StripeException) {
                log.warn("Stripe Connect token exchange failed: {}", e.message)
                return@forAccount "${props.baseUrl}/settings/payments?stripe=error"
            }
            tx.run {
                r.mode = "connect"
                r.status = "connected"
                r.externalAccountId = stripeAccount
                r.displayName = stripeAccount
                r.credentialsEncrypted = null
                r.settings = JSONB.valueOf("{}")
                r.connectedBy = (pending as Map<*, *>)["membership_id"]?.toString()?.let(UUID::fromString)
                r.connectedAt = Instant.now()
                r.lastError = null
                r.store()
            }
            "${props.baseUrl}/settings/payments?stripe=connected"
        }
    }

    /** Forgets the connection, and ends Honest Robin's access at Stripe where it can. */
    fun disconnect(m: Member): Disconnected {
        m.requireWritable()
        m.requireAdmin()
        val r = tx.run { integration() }
        val result = when {
            r == null || r.status != "connected" -> Disconnected(revoked = true)
            r.mode == "connect" -> deauthorize(m.accountId, r.externalAccountId)
            else -> Disconnected(
                revoked = false,
                note = "Honest Robin has deleted your key, but it works at Stripe until you roll it there (Developers → API keys). Delete the webhook endpoint there too.",
            )
        }
        tx.run { dsl.deleteFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.eq("stripe")).execute() }
        return result
    }

    /** Deleting an account ends Honest Robin's access at Stripe too, as disconnecting does. */
    @org.springframework.context.event.EventListener
    fun onAccountPurging(e: AccountPurging) {
        val r = DbContext.forAccount(e.accountId) { tx.run { integration() } } ?: return
        if (r.status != "connected" || r.mode != "connect") return
        deauthorize(e.accountId, r.externalAccountId).note?.let { log.warn("Deleting account {}: {}", e.accountId, it) }
    }

    private fun deauthorize(accountId: UUID, stripeAccount: String): Disconnected {
        // Stripe knows one connection between an account and Honest Robin, however many
        // workspaces use it: ending it here would cut off the others too.
        val shared = tx.system {
            dsl.fetchExists(
                dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.eq("stripe")).and(INTEGRATIONS.MODE.eq("connect")).and(INTEGRATIONS.STATUS.eq("connected"))
                    .and(INTEGRATIONS.EXTERNAL_ACCOUNT_ID.eq(stripeAccount)).and(INTEGRATIONS.ACCOUNT_ID.ne(accountId)),
            )
        }
        if (shared) {
            return Disconnected(false, "Another workspace on this instance still uses this Stripe account, so Stripe keeps Honest Robin's access until that one disconnects too.")
        }
        return try {
            stripe.deauthorize(stripeAccount)
            Disconnected(revoked = true)
        } catch (e: StripeException) {
            log.warn("Stripe didn't confirm deauthorizing {}: {}", stripeAccount, e.message)
            Disconnected(false, "Stripe didn't confirm that Honest Robin's access has ended (${e.message}). To be sure, remove Honest Robin from the platforms connected to your Stripe account.")
        }
    }

    private fun save(m: Member, mode: String, externalId: String, name: String?, creds: Map<String, String>) = tx.run {
        val r = integration() ?: dsl.newRecord(INTEGRATIONS).apply { accountId = m.accountId; kind = "stripe" }
        r.mode = mode
        r.status = "connected"
        r.externalAccountId = externalId
        r.displayName = name ?: externalId
        r.credentialsEncrypted = secrets.encrypt(json.writeValueAsString(creds))
        r.connectedBy = m.membershipId
        r.connectedAt = Instant.now()
        r.lastError = null
        r.store()
    }

    /** Whether the public page may offer "Pay now" for this account. */
    fun canPayOnline(accountId: UUID): Boolean =
        DbContext.forAccount(accountId) { tx.run { integration()?.status == "connected" } }

    /** Starts a Stripe Checkout for the amount still due on the invoice behind [token]. */
    fun startCheckout(token: String): CheckoutStart {
        val invoice = tx.system { dsl.selectFrom(INVOICES).where(INVOICES.PUBLIC_TOKEN.eq(token)).and(INVOICES.STATE.ne("draft")).fetchOne() }
            ?: throw NotFoundException("Invoice")
        return DbContext.anonymouslyForAccount(invoice.accountId) {
            tx.run {
                if (invoice.state !in setOf("sent", "open", "partially_paid") || invoice.dueMinor <= 0) throw ConflictException("not_payable", "This invoice has nothing left to pay")
                val r = integration()?.takeIf { it.status == "connected" } ?: throw ConflictException("no_online_payments", "This invoice can't be paid online")
                val (key, onBehalf) = when (r.mode) {
                    "connect" -> settings.platformSecretKey to r.externalAccountId
                    else -> (credentials(r)["secret_key"] ?: throw ConflictException("no_online_payments", "This invoice can't be paid online")) to null
                }
                val page = "${props.baseUrl}/i/$token"
                val params = linkedMapOf(
                    "mode" to "payment",
                    "client_reference_id" to invoice.id.toString(),
                    "line_items[0][quantity]" to "1",
                    "line_items[0][price_data][currency]" to invoice.currency.lowercase(),
                    "line_items[0][price_data][unit_amount]" to StripeClient.toStripeAmount(invoice.dueMinor, invoice.currency).toString(),
                    "line_items[0][price_data][product_data][name]" to "Invoice ${invoice.number}",
                    "metadata[invoice_id]" to invoice.id.toString(),
                    "metadata[account_id]" to invoice.accountId.toString(),
                    "payment_intent_data[metadata][invoice_id]" to invoice.id.toString(),
                    "success_url" to "$page?paid=1",
                    "cancel_url" to page,
                )
                invoice.sentTo?.firstOrNull()?.let { params["customer_email"] = it }
                // No application_fee_amount: Honest Robin takes nothing from the payment (spec §5.6).
                val session = try {
                    stripe.createCheckoutSession(key, onBehalf, params)
                } catch (e: StripeException) {
                    r.lastError = e.message
                    r.store()
                    throw BadRequestException("stripe_error", "Online payment is not available right now. Please pay by the other means on the invoice.")
                }
                CheckoutStart(session.url)
            }
        }
    }

    /**
     * A Stripe event. [accountId] is set for an account's own webhook endpoint (own key), and null
     * for the platform's Connect endpoint, where the event names the connected account.
     */
    fun webhook(accountId: UUID?, payload: String, signature: String?): Boolean {
        // The endpoint is public: check the signature before parsing anything, so only Stripe can
        // make us build a JSON tree. The secret is the platform's, or the account's own.
        val integration = if (accountId != null) {
            val own = tx.system {
                dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.ACCOUNT_ID.eq(accountId)).and(INTEGRATIONS.KIND.eq("stripe")).and(INTEGRATIONS.MODE.eq("api_key")).fetchOne()
            } ?: return false
            if (!StripeClient.verifySignature(payload, signature, credentials(own)["webhook_secret"] ?: "")) return false
            own
        } else {
            if (!StripeClient.verifySignature(payload, signature, settings.platformWebhookSecret)) return false
            null
        }
        val event = runCatching { json.readTree(payload) }.getOrNull() ?: return false
        if (integration == null && event["type"]?.asText() == "account.application.deauthorized") {
            // The owner removed Honest Robin in Stripe: stop saying it's connected.
            val acct = event["account"]?.asText() ?: return true
            val app = event["data"]?.get("object")?.get("id")?.asText()
            if (app != null && app != settings.connectClientId) return true
            val removed = tx.system {
                dsl.deleteFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.eq("stripe")).and(INTEGRATIONS.MODE.eq("connect")).and(INTEGRATIONS.EXTERNAL_ACCOUNT_ID.eq(acct)).execute()
            }
            log.info("Stripe account {} removed Honest Robin; {} connection(s) ended", acct, removed)
            return true
        }
        if (integration == null) {
            // The platform's Connect endpoint: the event names the connected account. One Stripe
            // account can be connected to several workspaces; the checkout names the workspace.
            val acct = event["account"]?.asText() ?: return true
            val candidates = tx.system {
                dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.eq("stripe")).and(INTEGRATIONS.MODE.eq("connect")).and(INTEGRATIONS.EXTERNAL_ACCOUNT_ID.eq(acct)).fetch()
            }
            val named = event["data"]?.get("object")?.get("metadata")?.get("account_id")?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val connected = candidates.firstOrNull { it.accountId == named } ?: candidates.singleOrNull() ?: return true
            return record(connected, event)
        }
        return record(integration, event)
    }

    private fun record(integration: IntegrationsRecord, event: JsonNode): Boolean {
        val type = event["type"]?.asText()
        if (type !in setOf("checkout.session.completed", "checkout.session.async_payment_succeeded")) return true
        val session = event["data"]["object"]
        if (session["payment_status"]?.asText() != "paid") return true
        // A payment with Stripe's test cards never settles a live connection's invoice, nor the
        // other way round: the event must be in the connection's own mode.
        if (event["livemode"]?.takeIf { it.isBoolean }?.asBoolean() != livemode(integration)) {
            log.warn("Stripe event {} is {}, the connection of account {} isn't; not recorded", event["id"]?.asText(), if (event["livemode"]?.asBoolean() == true) "live" else "in test mode", integration.accountId)
            return true
        }
        val invoiceId = session["metadata"]?.get("invoice_id")?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return true
        DbContext.forAccount(integration.accountId) {
            tx.run {
                val invoice = dsl.selectFrom(INVOICES).where(INVOICES.ID.eq(invoiceId)).fetchOne() ?: return@run
                val reference = session["payment_intent"]?.takeIf { !it.isNull }?.asText() ?: session["id"].asText()
                val currency = session["currency"]?.asText()?.uppercase() ?: invoice.currency
                if (currency != invoice.currency) {
                    log.warn("Stripe payment for invoice {} is in {}, the invoice in {}; not recorded", invoice.id, currency, invoice.currency)
                    return@run
                }
                val amount = StripeClient.fromStripeAmount(session["amount_total"].asLong(), currency)
                val created = event["created"]?.asLong()?.let(Instant::ofEpochSecond) ?: Instant.now()
                val inserted = dsl.insertInto(PAYMENTS).set(PAYMENTS.ACCOUNT_ID, invoice.accountId).set(PAYMENTS.INVOICE_ID, invoice.id)
                    .set(PAYMENTS.AMOUNT_MINOR, amount).set(PAYMENTS.PAID_AT, created).set(PAYMENTS.PAID_DATE, created.atZone(ZoneOffset.UTC).toLocalDate())
                    .set(PAYMENTS.METHOD, "stripe").set(PAYMENTS.EXTERNAL_ID, reference).set(PAYMENTS.RECORDED_BY, "Stripe")
                    .onConflictDoNothing().returning(PAYMENTS.ID).fetchOne()
                if (inserted != null) {
                    invoices.settle(invoice)
                    events.publishEvent(com.honestrobin.time.invoicing.PaymentRecorded(invoice.accountId, invoice.id, inserted.id))
                    funnel.event(invoice.accountId, Funnel.INVOICE_PAID_ONLINE, mapOf("currency" to currency))
                }
            }
        }
        return true
    }
}

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "payments", description = "Online invoice payments through Stripe")
class PaymentsController(private val service: OnlinePaymentService, private val recentAuth: RecentAuth) {
    @GetMapping("/stripe")
    fun status() = service.status(Current.member())

    @PostMapping("/stripe/key")
    fun connectWithKey(@RequestBody body: StripeKeyInput): PaymentsStatus {
        // Online payments go to the connected Stripe account.
        recentAuth.require()
        return service.connectWithKey(Current.member(), body)
    }

    @PostMapping("/stripe/connect")
    fun connect(): Map<String, String> {
        recentAuth.require()
        return mapOf("url" to service.connectUrl(Current.member()))
    }

    @DeleteMapping("/stripe")
    fun disconnect() = service.disconnect(Current.member())
}

@RestController
@Tag(name = "public", description = "Pages for people outside the account")
class PublicPaymentsController(private val service: OnlinePaymentService) {
    @PostMapping("/api/v1/public/invoices/{token}/checkout")
    fun checkout(@PathVariable token: String) = service.startCheckout(token)

    @GetMapping("/api/v1/public/stripe/callback")
    fun callback(@RequestParam state: String, @RequestParam(required = false) code: String?, @RequestParam(required = false) error: String?): ResponseEntity<Unit> =
        ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(service.connectCallback(state, code, error)).toString()).build()

    /** The platform's Connect endpoint. */
    @PostMapping("/webhooks/stripe")
    fun connectWebhook(@RequestBody payload: String, @RequestHeader("Stripe-Signature", required = false) signature: String?): ResponseEntity<Unit> =
        if (service.webhook(null, payload, signature)) ResponseEntity.ok().build() else ResponseEntity.badRequest().build()

    /** An account's own endpoint, when it connected with its own key. */
    @PostMapping("/webhooks/stripe/{accountId}")
    fun accountWebhook(@PathVariable accountId: UUID, @RequestBody payload: String, @RequestHeader("Stripe-Signature", required = false) signature: String?): ResponseEntity<Unit> =
        if (service.webhook(accountId, payload, signature)) ResponseEntity.ok().build() else ResponseEntity.badRequest().build()
}
