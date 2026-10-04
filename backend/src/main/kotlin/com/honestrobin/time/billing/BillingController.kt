// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.billing

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.honestrobin.time.accounts.SeatCounter
import com.honestrobin.time.accounts.SeatGate
import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.BILLING_EVENTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.SubscriptionsRecord
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.edition.CloudEditionOnly
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class PlanPrice(val interval: String, val currency: String, val perSeatPerMonthMinor: Long)

data class SubscriptionView(
    /** free (one person) or team. */
    val plan: String,
    val status: String,
    /** People who can sign in now; what a Team subscription is billed for. */
    val seatsUsed: Int,
    val seatsBilled: Int?,
    val interval: String?,
    val currency: String?,
    /** The price per seat this subscription keeps for as long as it runs (spec §14). */
    val lockedUnitPriceMinor: Long?,
    val currentPeriodEnd: Instant?,
    val cancelAt: Instant?,
    /** Whether Paddle is set up on this instance; without it there is nothing to buy. */
    val billingAvailable: Boolean,
    val prices: List<PlanPrice>,
)

data class CheckoutRequest(val interval: String = "year")

/** What Paddle.js needs to open the checkout in the browser. */
data class CheckoutView(
    val environment: String,
    val clientToken: String,
    val priceId: String,
    val quantity: Int,
    val email: String,
    val customData: Map<String, String>,
)

data class PortalView(val url: String)

/**
 * Honest Robin Cloud subscriptions (spec §14): Free for one person, Team per seat. Paddle is the
 * merchant of record; this keeps the state Paddle reports, keeps the seat count in step, and
 * never lets a subscription's price per seat go up (the trigger `subscriptions_price_lock` backs
 * this in the database).
 */
@Service
@CloudEditionOnly
class BillingService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val settings: PaddleSettings,
    private val prices: BillingPrices,
    private val seats: SeatCounter,
    private val freePlan: FreePlan,
    private val funnel: Funnel,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val paddle = PaddleClient(settings, json)

    private fun current(): SubscriptionsRecord? = dsl.selectFrom(SUBSCRIPTIONS).fetchOne()

    /** A subscription that pays for seats now. */
    fun isPaying(s: SubscriptionsRecord?) = s != null && s.status in setOf("trialing", "active", "past_due")

    @Transactional(readOnly = true)
    fun view(m: Member): SubscriptionView {
        m.requireAdmin()
        val s = current()
        val paying = isPaying(s)
        return SubscriptionView(
            plan = if (paying) "team" else "free", status = s?.status ?: "active", seatsUsed = seats.used(m.accountId),
            seatsBilled = s?.seats, interval = s?.billingInterval, currency = s?.currency, lockedUnitPriceMinor = s?.lockedUnitPriceMinor,
            currentPeriodEnd = s?.currentPeriodEnd, cancelAt = s?.cancelAt, billingAvailable = settings.configured,
            prices = listOf(PlanPrice("month", prices.currency, prices.teamMonthlyMinor), PlanPrice("year", prices.currency, prices.teamAnnualMonthlyMinor)),
        )
    }

    @Transactional(readOnly = true)
    fun checkout(m: Member, input: CheckoutRequest): CheckoutView {
        m.requireAdmin()
        if (!settings.configured) throw ConflictException("billing_unavailable", "Billing isn't set up on this instance")
        if (isPaying(current())) throw ConflictException("already_subscribed", "This account already has a subscription; change it in the billing portal")
        val priceId = when (input.interval) {
            "month" -> settings.teamMonthlyPriceId
            "year" -> settings.teamAnnualPriceId.ifBlank { throw ValidationException("interval", "Yearly billing isn't offered yet") }
            else -> throw ValidationException("interval", "Choose month or year")
        }
        val email = dsl.select(USERS.EMAIL).from(USERS).where(USERS.ID.eq(m.userId)).fetchOne()!!.value1()
        return CheckoutView(
            environment = settings.environment, clientToken = settings.clientToken, priceId = priceId,
            quantity = maxOf(1, seats.claimed(m.accountId)), email = email,
            // The browser hands this to Paddle; signed, so nobody can attach a subscription to another account.
            customData = mapOf("account_id" to m.accountId.toString(), "signature" to accountSignature(m.accountId)),
        )
    }

    @Transactional(readOnly = true)
    fun portal(m: Member): PortalView {
        m.requireAdmin()
        val s = current() ?: throw ConflictException("no_subscription", "This account has no subscription yet")
        val customer = s.externalCustomerId ?: throw ConflictException("no_subscription", "This account has no subscription yet")
        return PortalView(paddle.portalUrl(customer, s.externalId))
    }

    /** Handles a Paddle webhook; false when the signature doesn't check out. */
    fun webhook(payload: String, signature: String?): Boolean {
        // Signatures carry wall-clock time, so they are checked against it, not the app clock.
        if (!PaddleClient.verifySignature(payload, signature, settings.webhookSecret, Instant.now())) return false
        val event = json.readTree(payload)
        val type = event["event_type"]?.asText() ?: return true
        if (!type.startsWith("subscription.")) return true
        val data = event["data"]
        val externalId = data["id"].asText()
        val occurred = event["occurred_at"]?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        var duplicate: String? = null
        tx.system {
            // One event per subscription at a time, so a retry can't race a newer event.
            dsl.execute("select pg_advisory_xact_lock(hashtextextended(?, 7234004))", "paddle:$externalId")
            val existing = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.EXTERNAL_ID.eq(externalId)).fetchOne()
            val accountId = existing?.accountId ?: claimedAccount(data)
            if (accountId == null || !dsl.fetchExists(ACCOUNTS, ACCOUNTS.ID.eq(accountId))) {
                log.warn("Paddle event {} for subscription {} names no known account, or its signature is wrong; ignored", event["event_id"]?.asText(), externalId)
                return@system
            }
            val priceId = data["items"]?.firstOrNull()?.get("price")?.get("id")?.asText()
            if (existing == null && priceId !in setOf(settings.teamMonthlyPriceId, settings.teamAnnualPriceId).filter { it.isNotBlank() }) {
                log.error("Paddle subscription {} is on price {}, which isn't one of ours; ignored", externalId, priceId)
                return@system
            }
            val other = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(accountId)).and(SUBSCRIPTIONS.EXTERNAL_ID.ne(externalId)).fetchOne()
            if (other != null && isPaying(other)) {
                // Two checkouts at once (two tabs, say) make a second subscription. It's cancelled so
                // it never renews; its first payment needs a refund by a person.
                if (data["status"]?.asText() in setOf("trialing", "active", "past_due")) duplicate = externalId
                log.error("Paddle subscription {} is for account {}, which already pays through {}; cancelling it, refund its first payment", externalId, accountId, other.externalId)
                return@system
            }
            other?.delete()
            // Paddle retries: each event once.
            val fresh = dsl.insertInto(BILLING_EVENTS).set(BILLING_EVENTS.EVENT_ID, event["event_id"].asText()).set(BILLING_EVENTS.EVENT_TYPE, type)
                .set(BILLING_EVENTS.ACCOUNT_ID, accountId).onConflictDoNothing().execute()
            if (fresh == 0) return@system
            // Paddle doesn't promise order: an event older than the one last applied is only recorded.
            val last = existing?.lastEventAt
            if (last != null && (occurred == null || occurred.isBefore(last))) {
                log.info("Paddle event {} for subscription {} happened before the one already applied; not applied", event["event_id"]?.asText(), externalId)
                return@system
            }
            DbContext.forAccount(accountId) { apply(accountId, existing, data, occurred) }
        }
        duplicate?.let { id ->
            try {
                paddle.cancel(id)
            } catch (e: PaddleException) {
                log.error("Cancelling duplicate Paddle subscription {} failed: {}", id, e.message)
            }
        }
        return true
    }

    /** The account a new subscription is for, if its signature from our checkout checks out. */
    private fun claimedAccount(data: JsonNode): UUID? {
        val custom = data["custom_data"] ?: return null
        val account = custom["account_id"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val signature = custom["signature"]?.asText() ?: return null
        return account.takeIf { java.security.MessageDigest.isEqual(signature.toByteArray(), accountSignature(it).toByteArray()) }
    }

    private fun accountSignature(accountId: UUID): String = PaddleClient.sign(settings.webhookSecret, 0, "account:$accountId")

    private fun apply(accountId: UUID, existing: SubscriptionsRecord?, data: JsonNode, occurred: Instant?) {
        val item = data["items"]?.firstOrNull() ?: return
        val unitPrice = item["price"]["unit_price"]["amount"].asText().toLong()
        val r = existing ?: dsl.newRecord(SUBSCRIPTIONS).apply {
            this.accountId = accountId
            externalId = data["id"].asText()
            externalPriceId = item["price"]["id"].asText()
            currency = data["currency_code"].asText()
            lockedUnitPriceMinor = unitPrice
        }
        val isNew = existing == null
        if (!isNew && unitPrice > r.lockedUnitPriceMinor) {
            // Paddle may only charge what was locked; this needs a person, not a silent change.
            log.error("Paddle reports {} per seat for subscription {}, above the locked {}; keeping the lock", unitPrice, r.externalId, r.lockedUnitPriceMinor)
        } else {
            r.lockedUnitPriceMinor = minOf(r.lockedUnitPriceMinor, unitPrice)
        }
        r.externalCustomerId = data["customer_id"]?.asText() ?: r.externalCustomerId
        r.billingInterval = data["billing_cycle"]?.get("interval")?.asText() ?: r.billingInterval ?: "month"
        r.status = data["status"].asText()
        r.seats = item["quantity"]?.asInt() ?: r.seats ?: 1
        r.currentPeriodEnd = data["current_billing_period"]?.get("ends_at")?.asText()?.let(Instant::parse)
        r.cancelAt = data["scheduled_change"]?.takeIf { it["action"]?.asText() == "cancel" }?.get("effective_at")?.asText()?.let(Instant::parse)
        r.canceledAt = data["canceled_at"]?.takeIf { !it.isNull }?.asText()?.let(Instant::parse)
        r.lastEventAt = occurred ?: r.lastEventAt
        r.store()
        if (isNew) funnel.event(accountId, Funnel.SUBSCRIPTION_STARTED, mapOf("interval" to r.billingInterval, "seats" to r.seats))
        if (isPaying(r)) reactivate(accountId) else lapseIfOverFree(accountId)
    }

    /** Keeps Paddle's seat count in step with the people who can sign in (the job). */
    fun syncSeats(): Int {
        if (!settings.configured) return 0
        val subs = tx.system { dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.STATUS.`in`("trialing", "active", "past_due")).fetch() }
        var changed = 0
        for (s in subs) {
            val used = maxOf(1, tx.system { seats.used(s.accountId) })
            if (used == s.seats) continue
            try {
                paddle.updateQuantity(s.externalId, s.externalPriceId, used)
                tx.system { dsl.update(SUBSCRIPTIONS).set(SUBSCRIPTIONS.SEATS, used).where(SUBSCRIPTIONS.ID.eq(s.id)).execute() }
                changed++
            } catch (e: PaddleException) {
                log.warn("Seat update for subscription {} failed: {}", s.externalId, e.message)
            }
        }
        return changed
    }

    /** Accounts whose subscription ended and that have more people than the free plan: read-only, export still works. */
    fun lapseDue(): Int {
        val ended = tx.system {
            dsl.select(SUBSCRIPTIONS.ACCOUNT_ID).from(SUBSCRIPTIONS)
                .where(SUBSCRIPTIONS.STATUS.`in`("canceled", "paused")).fetch(SUBSCRIPTIONS.ACCOUNT_ID)
        }
        return ended.count { tx.system { lapseIfOverFree(it) } }
    }

    private fun lapseIfOverFree(accountId: UUID): Boolean {
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).fetchOne() ?: return false
        if (account.status != "active" || seats.used(accountId) <= freePlan.seats) return false
        account.status = "lapsed"
        account.lapsedAt = Instant.now(clock)
        account.store()
        return true
    }

    private fun reactivate(accountId: UUID) {
        dsl.update(ACCOUNTS).set(ACCOUNTS.STATUS, "active").setNull(ACCOUNTS.LAPSED_AT)
            .where(ACCOUNTS.ID.eq(accountId)).and(ACCOUNTS.STATUS.eq("lapsed")).execute()
    }

}

/** On Honest Robin Cloud a second person needs a Team subscription (the free plan is one person). */
@Component
@Primary
@CloudEditionOnly
class SubscriptionSeatGate(
    private val dsl: DSLContext,
    private val seats: SeatCounter,
    private val settings: PaddleSettings,
    private val freePlan: FreePlan,
) : SeatGate {
    override fun requireSeat(accountId: UUID) {
        // Without Paddle there is nothing to buy, so nothing to hold back.
        if (!settings.configured) return
        val paying = dsl.fetchExists(SUBSCRIPTIONS, SUBSCRIPTIONS.ACCOUNT_ID.eq(accountId).and(SUBSCRIPTIONS.STATUS.`in`("trialing", "active", "past_due")))
        if (!paying && seats.claimed(accountId) >= freePlan.seats) {
            val who = if (freePlan.seats == 1) "one person" else "${freePlan.seats} people"
            throw ApiException(HttpStatus.PAYMENT_REQUIRED, "subscription_required", "The free plan is for $who. Start a Team subscription to invite your team.")
        }
    }
}

/** Paddle.js runs the checkout in the browser: its script and frames are allowed where billing is set up. (VERIFY the origins with Paddle.) */
@Component
@CloudEditionOnly
class PaddleCsp(private val settings: PaddleSettings) : com.honestrobin.time.platform.security.CspContributor {
    override fun sources() = if (!settings.configured) {
        com.honestrobin.time.platform.security.CspSources()
    } else {
        com.honestrobin.time.platform.security.CspSources(
            script = listOf("https://cdn.paddle.com"),
            frame = listOf("https://buy.paddle.com", "https://sandbox-buy.paddle.com"),
            connect = listOf("https://*.paddle.com"),
            img = listOf("https://*.paddle.com"),
        )
    }
}

@Configuration
@CloudEditionOnly
class BillingJobsConfig {
    @Bean
    fun billingTask(@org.springframework.context.annotation.Lazy billing: BillingService): RecurringTask<Void> =
        Tasks.recurring("billing-sync", FixedDelay.of(Duration.ofMinutes(15))).execute { _, _ ->
            billing.syncSeats()
            billing.lapseDue()
        }
}

@RestController
@CloudEditionOnly
@RequestMapping("/api/v1/billing")
@Tag(name = "billing", description = "Honest Robin Cloud subscription (cloud edition only)")
class BillingController(private val billing: BillingService) {
    @GetMapping("/subscription")
    fun subscription(): SubscriptionView = billing.view(Current.member())

    @PostMapping("/checkout")
    @Operation(summary = "Details for opening the Paddle checkout in the browser")
    fun checkout(@RequestBody body: CheckoutRequest): CheckoutView = billing.checkout(Current.member(), body)

    @PostMapping("/portal")
    @Operation(summary = "A link to Paddle's customer portal")
    fun portal(): PortalView = billing.portal(Current.member())
}

@RestController
@CloudEditionOnly
class PaddleWebhookController(private val billing: BillingService) {
    @PostMapping("/webhooks/paddle")
    fun webhook(@RequestBody payload: String, @RequestHeader(name = "Paddle-Signature", required = false) signature: String?): ResponseEntity<Unit> =
        if (billing.webhook(payload, signature)) ResponseEntity.ok().build() else ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
}
