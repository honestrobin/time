// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounting

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.honestrobin.time.db.Tables.ACCOUNTING_SYNC_ITEMS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.EXTERNAL_LINKS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.INVOICE_LINES
import com.honestrobin.time.db.Tables.PAYMENTS
import com.honestrobin.time.db.tables.records.AccountingSyncItemsRecord
import com.honestrobin.time.db.tables.records.IntegrationsRecord
import com.honestrobin.time.export.AccountPurging
import com.honestrobin.time.invoicing.InvoiceIssued
import com.honestrobin.time.invoicing.InvoiceService
import com.honestrobin.time.invoicing.PaymentRecorded
import com.honestrobin.time.platform.Disconnected
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.crypto.SecretBox
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.OAuthStates
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AccountingConnectionView(
    val kind: String,
    val label: String,
    /** Whether this instance has an app registered with the provider. */
    val available: Boolean,
    val connected: Boolean,
    val organisation: String?,
    val mapping: Mapping?,
    val failed: Int,
    val pending: Int,
)

data class AccountingOptionsView(
    val taxCodes: List<Choice>,
    val items: List<Choice>,
    val salesAccounts: List<Choice>,
    val paymentAccounts: List<Choice>,
    /** Our taxes in use, each needing a tax code: key and how to show it. */
    val ourTaxes: List<Choice>,
)

data class SyncItemView(
    val id: UUID,
    val provider: String,
    val entityType: String,
    val entityId: UUID,
    /** The invoice number, for payments too. */
    val invoiceNumber: String?,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val nextAttemptAt: Instant,
    val externalId: String?,
    val updatedAt: Instant,
)

/**
 * Pushing invoices and payments to QuickBooks Online and Xero (spec §8): one-way, idempotent
 * (external_links plus the providers' idempotency keys), through a queue that retries with
 * backoff and shows what failed and why.
 */
@Service
class AccountingService(
    private val dsl: DSLContext,
    private val tx: Tx,
    providers: List<AccountingProvider>,
    private val invoices: InvoiceService,
    private val secrets: SecretBox,
    private val props: HonestRobinProperties,
    private val json: ObjectMapper,
    private val oauth: OAuthStates,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val providers = providers.associateBy { it.kind }

    private fun provider(kind: String) = providers[kind] ?: throw NotFoundException("Accounting system")

    private fun integration(kind: String): IntegrationsRecord? = dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.eq(kind)).fetchOne()

    private fun redirectUri(kind: String) = "${props.baseUrl}/api/v1/public/accounting/$kind/callback"

    private fun mapping(r: IntegrationsRecord): Mapping =
        r.settings?.data()?.let { runCatching { json.readValue<Map<String, Any?>>(it)["mapping"] }.getOrNull() }
            ?.let { json.convertValue(it, Mapping::class.java) } ?: Mapping()

    private fun tokens(r: IntegrationsRecord): OAuthTokens = json.readValue(secrets.decrypt(r.credentialsEncrypted!!), OAuthTokens::class.java)

    private fun saveTokens(r: IntegrationsRecord, t: OAuthTokens) {
        r.credentialsEncrypted = secrets.encrypt(json.writeValueAsString(t))
        r.externalAccountId = t.orgId
        r.displayName = t.orgName ?: r.displayName
    }

    private fun settingsWith(r: IntegrationsRecord, key: String, value: Any?): JSONB {
        val current = r.settings?.data()?.let { runCatching { json.readValue<MutableMap<String, Any?>>(it) }.getOrNull() } ?: mutableMapOf()
        if (value == null) current.remove(key) else current[key] = value
        return JSONB.valueOf(json.writeValueAsString(current))
    }

    /** Fresh tokens, refreshed and saved when they're about to expire. */
    private fun fresh(r: IntegrationsRecord): OAuthTokens {
        val t = tokens(r)
        if (t.expiresAt.isAfter(Instant.now().plusSeconds(60))) return t
        val refreshed = provider(r.kind).refresh(t)
        tx.run {
            val row = dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.ID.eq(r.id)).fetchOne()!!
            saveTokens(row, refreshed)
            row.store()
        }
        return refreshed
    }

    // ---- connections -------------------------------------------------------------

    fun connections(m: Member): List<AccountingConnectionView> {
        invoices.requireAccess(m)
        return tx.run {
            val counts = dsl.select(ACCOUNTING_SYNC_ITEMS.PROVIDER, ACCOUNTING_SYNC_ITEMS.STATUS, DSL.count()).from(ACCOUNTING_SYNC_ITEMS)
                .groupBy(ACCOUNTING_SYNC_ITEMS.PROVIDER, ACCOUNTING_SYNC_ITEMS.STATUS).fetch()
            fun count(kind: String, status: String) = counts.firstOrNull { it.value1() == kind && it.value2() == status }?.value3() ?: 0
            providers.values.sortedBy { it.label }.map { p ->
                val r = integration(p.kind)?.takeIf { it.status == "connected" }
                AccountingConnectionView(p.kind, p.label, p.configured, r != null, r?.displayName, r?.let(::mapping), count(p.kind, "failed"), count(p.kind, "pending"))
            }
        }
    }

    /** Where to send the admin to authorise Honest Robin. */
    fun connectUrl(m: Member, kind: String): String {
        m.requireWritable()
        m.requireAdmin()
        val p = provider(kind)
        if (!p.configured) throw ConflictException("not_available", "${p.label} isn't set up on this instance. The admin of the instance registers an app with ${p.label} first (see docs/self-host.md).")
        val (state, pending) = oauth.start(m)
        tx.run {
            val r = integration(kind) ?: dsl.newRecord(INTEGRATIONS).apply { accountId = m.accountId; this.kind = kind; mode = "connect"; status = "error" }
            r.settings = settingsWith(r, OAuthStates.KEY, pending)
            r.store()
        }
        return p.authorizeUrl(state, redirectUri(kind))
    }

    /**
     * The provider sends the admin back here; returns where to send them in the app. Only the
     * browser session that started connecting, still an admin, can finish it.
     */
    fun callback(kind: String, params: Map<String, String>): String {
        val back = "${props.baseUrl}/settings/accounting"
        val p = providers[kind] ?: return "$back?accounting=error"
        val (accountId, token) = oauth.parse(params["state"]) ?: return "$back?accounting=error"
        val code = params["code"] ?: return "$back?accounting=error"
        return DbContext.forAccount(accountId) {
            val r = tx.run { integration(kind) } ?: return@forAccount "$back?accounting=error"
            val pending = r.settings?.data()?.let { runCatching { json.readValue<Map<String, Any?>>(it)[OAuthStates.KEY] }.getOrNull() }
            if (!oauth.matches(pending, accountId, token)) return@forAccount "$back?accounting=error"
            try {
                val t = p.exchange(code, redirectUri(kind), params)
                tx.run {
                    val row = integration(kind)!!
                    saveTokens(row, t)
                    row.status = "connected"
                    row.lastError = null
                    row.connectedBy = (pending as Map<*, *>)["membership_id"]?.toString()?.let(UUID::fromString)
                    row.connectedAt = Instant.now()
                    // "oauth_state" held the raw state before 4 October 2026.
                    row.settings = settingsWith(row, OAuthStates.KEY, null)
                    row.settings = settingsWith(row, "oauth_state", null)
                    row.store()
                }
                "$back?accounting=connected&provider=$kind"
            } catch (e: AccountingException) {
                log.warn("Connecting {} for account {} failed: {}", kind, accountId, e.message)
                "$back?accounting=error"
            }
        }
    }

    /** Forgets the connection, and ends Honest Robin's access at the provider. */
    fun disconnect(m: Member, kind: String): Disconnected {
        m.requireWritable()
        m.requireAdmin()
        val p = provider(kind)
        val r = tx.run { integration(kind) }
        val result = if (r?.status == "connected" && r.credentialsEncrypted != null) revoke(p, r) else Disconnected(revoked = true)
        tx.run {
            integration(kind)?.delete()
            dsl.deleteFrom(ACCOUNTING_SYNC_ITEMS).where(ACCOUNTING_SYNC_ITEMS.PROVIDER.eq(kind)).and(ACCOUNTING_SYNC_ITEMS.STATUS.ne("done")).execute()
        }
        return result
    }

    private fun revoke(p: AccountingProvider, r: IntegrationsRecord): Disconnected = try {
        p.revoke(fresh(r))
        Disconnected(revoked = true)
    } catch (e: AccountingException) {
        log.warn("{} didn't confirm revoking access for account {}: {}", p.label, r.accountId, e.message)
        Disconnected(false, "${p.label} didn't confirm that Honest Robin's access has ended (${e.message}). To be sure, remove Honest Robin from the connected apps in ${p.label}.")
    }

    /** Deleting an account ends Honest Robin's access to its books too, as disconnecting does. */
    @EventListener
    fun onAccountPurging(e: AccountPurging) = DbContext.forAccount(e.accountId) {
        tx.run { dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.`in`(providers.keys)).and(INTEGRATIONS.STATUS.eq("connected")).fetch() }
            .filter { it.credentialsEncrypted != null }
            .forEach { r -> revoke(providers.getValue(r.kind), r) }
    }

    fun options(m: Member, kind: String): AccountingOptionsView {
        invoices.requireAccess(m)
        val r = tx.run { integration(kind)?.takeIf { it.status == "connected" } } ?: throw ConflictException("not_connected", "Connect ${provider(kind).label} first")
        val o = try {
            provider(kind).options(fresh(r))
        } catch (e: AccountingException) {
            throw ConflictException("provider_error", "${provider(kind).label}: ${e.message}")
        }
        return AccountingOptionsView(o.taxCodes, o.items, o.salesAccounts, o.paymentAccounts, tx.run { ourTaxes() })
    }

    fun updateMapping(m: Member, kind: String, mapping: Mapping): AccountingConnectionView {
        m.requireWritable()
        m.requireAdmin()
        tx.run {
            val r = integration(kind)?.takeIf { it.status == "connected" } ?: throw ConflictException("not_connected", "Connect ${provider(kind).label} first")
            r.settings = settingsWith(r, "mapping", mapping)
            r.store()
            // New choices may fix what failed before.
            dsl.update(ACCOUNTING_SYNC_ITEMS).set(ACCOUNTING_SYNC_ITEMS.STATUS, "pending").set(ACCOUNTING_SYNC_ITEMS.NEXT_ATTEMPT_AT, Instant.now())
                .set(ACCOUNTING_SYNC_ITEMS.ATTEMPTS, 0).where(ACCOUNTING_SYNC_ITEMS.PROVIDER.eq(kind)).and(ACCOUNTING_SYNC_ITEMS.STATUS.eq("failed")).execute()
        }
        return connections(m).first { it.kind == kind }
    }

    /** The tax keys our invoices use: VAT categories with their rates, or the named taxes. */
    private fun ourTaxes(): List<Choice> {
        val vat = dsl.selectDistinct(INVOICE_LINES.VAT_CATEGORY_CODE, INVOICE_LINES.VAT_PERCENT).from(INVOICE_LINES)
            .join(INVOICES).on(INVOICES.ID.eq(INVOICE_LINES.INVOICE_ID)).where(INVOICES.VAT_MODE.isNotNull).fetch()
            .map { taxKeyVat(it.value1() ?: "S", it.value2()) }
        val named = listOf("tax1", "tax1+tax2", "tax2", "none")
        return (vat + named).distinct().map { Choice(it, taxLabel(it)) }
    }

    // ---- the queue -----------------------------------------------------------------

    // Scoped to the account: the Stripe webhook publishes payments with row-level security off.
    private fun connectedForPush(accountId: UUID): List<String> =
        dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.ACCOUNT_ID.eq(accountId)).and(INTEGRATIONS.KIND.`in`(providers.keys)).and(INTEGRATIONS.STATUS.eq("connected")).fetch()
            .filter { mapping(it).autoPush }.map { it.kind }

    private fun enqueue(accountId: UUID, kind: String, type: String, entityId: UUID) {
        dsl.insertInto(ACCOUNTING_SYNC_ITEMS)
            .set(ACCOUNTING_SYNC_ITEMS.ACCOUNT_ID, accountId).set(ACCOUNTING_SYNC_ITEMS.PROVIDER, kind)
            .set(ACCOUNTING_SYNC_ITEMS.ENTITY_TYPE, type).set(ACCOUNTING_SYNC_ITEMS.ENTITY_ID, entityId)
            .onConflict(ACCOUNTING_SYNC_ITEMS.ACCOUNT_ID, ACCOUNTING_SYNC_ITEMS.PROVIDER, ACCOUNTING_SYNC_ITEMS.ENTITY_TYPE, ACCOUNTING_SYNC_ITEMS.ENTITY_ID)
            .doUpdate().set(ACCOUNTING_SYNC_ITEMS.STATUS, DSL.`when`(ACCOUNTING_SYNC_ITEMS.STATUS.eq("done"), "done").otherwise("pending"))
            .set(ACCOUNTING_SYNC_ITEMS.NEXT_ATTEMPT_AT, Instant.now())
            .execute()
    }

    @EventListener
    fun onInvoiceIssued(e: InvoiceIssued) = connectedForPush(e.accountId).forEach { enqueue(e.accountId, it, "invoice", e.invoiceId) }

    @EventListener
    fun onPayment(e: PaymentRecorded) = connectedForPush(e.accountId).forEach { enqueue(e.accountId, it, "payment", e.paymentId) }

    /** Pushes an issued invoice (and its payments) now, also if automatic pushing is off. */
    fun push(m: Member, invoiceId: UUID): List<SyncItemView> {
        m.requireWritable()
        invoices.requireAccess(m)
        tx.run {
            val inv = invoices.load(invoiceId)
            if (inv.number == null || inv.state == "draft") throw ConflictException("not_issued", "Send or mark the invoice as sent first")
            val kinds = dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.`in`(providers.keys)).and(INTEGRATIONS.STATUS.eq("connected")).fetch().map { it.kind }
            if (kinds.isEmpty()) throw ConflictException("not_connected", "Connect QuickBooks or Xero in Settings first")
            val payments = dsl.select(PAYMENTS.ID).from(PAYMENTS).where(PAYMENTS.INVOICE_ID.eq(invoiceId)).fetch(PAYMENTS.ID)
            kinds.forEach { k ->
                enqueue(m.accountId, k, "invoice", invoiceId)
                payments.forEach { enqueue(m.accountId, k, "payment", it) }
                // A manual push is also a retry.
                dsl.update(ACCOUNTING_SYNC_ITEMS).set(ACCOUNTING_SYNC_ITEMS.STATUS, "pending").set(ACCOUNTING_SYNC_ITEMS.ATTEMPTS, 0)
                    .where(ACCOUNTING_SYNC_ITEMS.PROVIDER.eq(k)).and(ACCOUNTING_SYNC_ITEMS.STATUS.eq("failed"))
                    .and(ACCOUNTING_SYNC_ITEMS.ENTITY_ID.`in`(payments + invoiceId)).execute()
            }
        }
        processAccount(m.accountId)
        return forInvoice(m, invoiceId)
    }

    fun retry(m: Member, itemId: UUID): SyncItemView {
        m.requireWritable()
        invoices.requireAccess(m)
        tx.run {
            dsl.update(ACCOUNTING_SYNC_ITEMS).set(ACCOUNTING_SYNC_ITEMS.STATUS, "pending").set(ACCOUNTING_SYNC_ITEMS.ATTEMPTS, 0)
                .set(ACCOUNTING_SYNC_ITEMS.NEXT_ATTEMPT_AT, Instant.now()).where(ACCOUNTING_SYNC_ITEMS.ID.eq(itemId)).execute()
                .also { if (it == 0) throw NotFoundException("Sync item") }
        }
        processAccount(m.accountId)
        return tx.run { views(dsl.selectFrom(ACCOUNTING_SYNC_ITEMS).where(ACCOUNTING_SYNC_ITEMS.ID.eq(itemId)).fetch()).single() }
    }

    fun forInvoice(m: Member, invoiceId: UUID): List<SyncItemView> {
        invoices.requireAccess(m)
        return tx.run {
            val payments = dsl.select(PAYMENTS.ID).from(PAYMENTS).where(PAYMENTS.INVOICE_ID.eq(invoiceId)).fetch(PAYMENTS.ID)
            views(dsl.selectFrom(ACCOUNTING_SYNC_ITEMS).where(ACCOUNTING_SYNC_ITEMS.ENTITY_ID.`in`(payments + invoiceId)).orderBy(ACCOUNTING_SYNC_ITEMS.CREATED_AT).fetch())
        }
    }

    fun queue(m: Member, kind: String, status: String?): List<SyncItemView> {
        invoices.requireAccess(m)
        return tx.run {
            views(
                dsl.selectFrom(ACCOUNTING_SYNC_ITEMS).where(ACCOUNTING_SYNC_ITEMS.PROVIDER.eq(kind))
                    .and(if (status != null) ACCOUNTING_SYNC_ITEMS.STATUS.eq(status) else DSL.noCondition())
                    .orderBy(ACCOUNTING_SYNC_ITEMS.UPDATED_AT.desc()).limit(100).fetch(),
            )
        }
    }

    /** The background job: every due item, account by account. */
    fun processDue(): Int {
        val accounts = tx.system {
            dsl.selectDistinct(ACCOUNTING_SYNC_ITEMS.ACCOUNT_ID).from(ACCOUNTING_SYNC_ITEMS)
                .where(ACCOUNTING_SYNC_ITEMS.STATUS.eq("pending")).and(ACCOUNTING_SYNC_ITEMS.NEXT_ATTEMPT_AT.le(Instant.now())).fetch(ACCOUNTING_SYNC_ITEMS.ACCOUNT_ID)
        }
        return accounts.sumOf { processAccount(it) }
    }

    fun processAccount(accountId: UUID): Int = DbContext.forAccount(accountId) {
        // Invoices before payments: a payment needs its invoice over there.
        val due = tx.run {
            dsl.selectFrom(ACCOUNTING_SYNC_ITEMS).where(ACCOUNTING_SYNC_ITEMS.STATUS.eq("pending")).and(ACCOUNTING_SYNC_ITEMS.NEXT_ATTEMPT_AT.le(Instant.now()))
                .orderBy(DSL.`when`(ACCOUNTING_SYNC_ITEMS.ENTITY_TYPE.eq("invoice"), 0).otherwise(1), ACCOUNTING_SYNC_ITEMS.CREATED_AT).limit(200).fetch()
        }
        due.count { process(it) }
    }

    private fun process(item: AccountingSyncItemsRecord): Boolean {
        val p = providers[item.provider] ?: return false
        return try {
            val external = tx.run {
                val r = integration(item.provider)?.takeIf { it.status == "connected" } ?: throw AccountingException(409, "${p.label} isn't connected")
                val t = fresh(r)
                val mapping = mapping(r)
                when (item.entityType) {
                    "invoice" -> pushInvoice(p, t, mapping, item)
                    else -> pushPayment(p, t, mapping, item)
                }
            }
            tx.run {
                dsl.update(ACCOUNTING_SYNC_ITEMS).set(ACCOUNTING_SYNC_ITEMS.STATUS, "done").set(ACCOUNTING_SYNC_ITEMS.EXTERNAL_ID, external)
                    .set(ACCOUNTING_SYNC_ITEMS.LAST_ERROR, null as String?).set(ACCOUNTING_SYNC_ITEMS.ATTEMPTS, item.attempts + 1)
                    .where(ACCOUNTING_SYNC_ITEMS.ID.eq(item.id)).execute()
            }
            true
        } catch (e: Exception) {
            val attempts = item.attempts + 1
            // Waits grow: 1 minute, 5, 30, 2 hours, 12 hours; then it's left for a person.
            val wait = listOf(1L, 5, 30, 120, 720).getOrNull(attempts - 1)
            val message = if (e is AccountingException) "${p.label}: ${e.message}" else (e.message ?: e.javaClass.simpleName)
            // Missing settings won't fix themselves: fail at once and say what to choose.
            val needsPerson = e is AccountingException && e.status == 422
            tx.run {
                dsl.update(ACCOUNTING_SYNC_ITEMS)
                    .set(ACCOUNTING_SYNC_ITEMS.STATUS, if (wait == null || needsPerson) "failed" else "pending")
                    .set(ACCOUNTING_SYNC_ITEMS.ATTEMPTS, attempts).set(ACCOUNTING_SYNC_ITEMS.LAST_ERROR, message.take(1000))
                    .set(ACCOUNTING_SYNC_ITEMS.NEXT_ATTEMPT_AT, Instant.now().plus(Duration.ofMinutes(wait ?: 0)))
                    .where(ACCOUNTING_SYNC_ITEMS.ID.eq(item.id)).execute()
            }
            log.info("Accounting push {} {} to {} failed (attempt {}): {}", item.entityType, item.entityId, item.provider, attempts, message)
            false
        }
    }

    private fun link(kind: String, type: String, entityId: UUID): String? =
        dsl.select(EXTERNAL_LINKS.EXTERNAL_ID).from(EXTERNAL_LINKS).where(EXTERNAL_LINKS.SYSTEM.eq(kind)).and(EXTERNAL_LINKS.ENTITY_TYPE.eq(type))
            .and(EXTERNAL_LINKS.ENTITY_ID.eq(entityId)).fetchOne()?.value1()

    private fun saveLink(accountId: UUID, kind: String, type: String, entityId: UUID, externalId: String) {
        dsl.insertInto(EXTERNAL_LINKS).set(EXTERNAL_LINKS.ACCOUNT_ID, accountId).set(EXTERNAL_LINKS.SYSTEM, kind).set(EXTERNAL_LINKS.ENTITY_TYPE, type)
            .set(EXTERNAL_LINKS.ENTITY_ID, entityId).set(EXTERNAL_LINKS.EXTERNAL_ID, externalId).onConflictDoNothing().execute()
    }

    private fun pushInvoice(p: AccountingProvider, t: OAuthTokens, mapping: Mapping, item: AccountingSyncItemsRecord): String {
        // Created before (and possibly the answer got lost): nothing to do.
        link(p.kind, "invoice", item.entityId)?.let { return it }
        val inv = dsl.selectFrom(INVOICES).where(INVOICES.ID.eq(item.entityId)).fetchOne() ?: throw AccountingException(404, "The invoice no longer exists")
        if (inv.number == null) throw AccountingException(409, "The invoice has no number yet")
        // Everything that needs a person's choice is checked before anything is created over there.
        val lines = dsl.selectFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(inv.id)).orderBy(INVOICE_LINES.POSITION).fetch().map { l ->
            val key = if (inv.vatMode != null) taxKeyVat(l.vatCategoryCode ?: "S", l.vatPercent) else taxKeyNamed(l.tax1Applies && inv.tax1Percent != null, l.tax2Applies && inv.tax2Percent != null)
            val code = mapping.taxCodes[key] ?: throw AccountingException(422, "Choose a ${p.label} tax code for ${taxLabel(key)} in Settings → Accounting")
            LineData(l.description?.ifBlank { null } ?: "Service", l.quantity, Money.fromMinor(l.unitPriceMinor, inv.currency), code)
        }
        p.checkMapping(mapping)
        val client = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(inv.clientId)).fetchOne()!!
        val customer = link(p.kind, "client", client.id) ?: run {
            val email = dsl.select(CLIENT_CONTACTS.EMAIL).from(CLIENT_CONTACTS).where(CLIENT_CONTACTS.CLIENT_ID.eq(client.id)).and(CLIENT_CONTACTS.EMAIL.isNotNull)
                .orderBy(CLIENT_CONTACTS.IS_INVOICE_RECIPIENT.desc()).limit(1).fetchOne()?.value1()
            p.findOrCreateCustomer(t, CustomerData(client.name, email, client.currency)).also { saveLink(inv.accountId, p.kind, "client", client.id, it) }
        }
        val created = p.createInvoice(
            t, InvoiceData(inv.number, inv.issueDate, inv.dueDate, inv.currency, customer, lines, inv.discountPercent, inv.purchaseOrder ?: inv.subject),
            mapping, item.id.toString(),
        )
        saveLink(inv.accountId, p.kind, "invoice", inv.id, created)
        return created
    }

    private fun pushPayment(p: AccountingProvider, t: OAuthTokens, mapping: Mapping, item: AccountingSyncItemsRecord): String {
        link(p.kind, "payment", item.entityId)?.let { return it }
        val pay = dsl.selectFrom(PAYMENTS).where(PAYMENTS.ID.eq(item.entityId)).fetchOne() ?: throw AccountingException(404, "The payment was deleted")
        val inv = dsl.selectFrom(INVOICES).where(INVOICES.ID.eq(pay.invoiceId)).fetchOne()!!
        // The invoice goes first; until it's there, the payment waits.
        val invoiceExternal = link(p.kind, "invoice", inv.id) ?: run {
            enqueue(inv.accountId, p.kind, "invoice", inv.id)
            throw AccountingException(409, "Waiting for the invoice to be pushed first")
        }
        val customer = link(p.kind, "client", inv.clientId)!!
        val created = p.createPayment(t, PaymentData(invoiceExternal, customer, Money.fromMinor(pay.amountMinor, inv.currency), pay.paidDate, inv.currency), mapping, item.id.toString())
        saveLink(inv.accountId, p.kind, "payment", pay.id, created)
        return created
    }

    private fun views(rows: List<AccountingSyncItemsRecord>): List<SyncItemView> {
        if (rows.isEmpty()) return emptyList()
        val paymentInvoice = dsl.select(PAYMENTS.ID, PAYMENTS.INVOICE_ID).from(PAYMENTS).where(PAYMENTS.ID.`in`(rows.map { it.entityId })).fetchMap(PAYMENTS.ID, PAYMENTS.INVOICE_ID)
        val numbers = dsl.select(INVOICES.ID, INVOICES.NUMBER).from(INVOICES)
            .where(INVOICES.ID.`in`(rows.map { it.entityId } + paymentInvoice.values)).fetchMap(INVOICES.ID, INVOICES.NUMBER)
        return rows.map {
            val invoice = if (it.entityType == "invoice") it.entityId else paymentInvoice[it.entityId]
            SyncItemView(it.id, it.provider, it.entityType, it.entityId, invoice?.let(numbers::get), it.status, it.attempts, it.lastError, it.nextAttemptAt, it.externalId, it.updatedAt)
        }
    }

    companion object {
        fun taxKeyVat(category: String, percent: BigDecimal?): String = "$category:${(percent ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString()}"

        fun taxKeyNamed(tax1: Boolean, tax2: Boolean): String = when {
            tax1 && tax2 -> "tax1+tax2"
            tax1 -> "tax1"
            tax2 -> "tax2"
            else -> "none"
        }

        fun taxLabel(key: String): String = when (key) {
            "tax1" -> "the first tax"
            "tax2" -> "the second tax"
            "tax1+tax2" -> "both taxes"
            "none" -> "lines without tax"
            else -> key.split(':').let { (cat, pct) -> "VAT $cat $pct%" }
        }
    }
}

@Configuration
class AccountingJobsConfig {
    @Bean
    fun accountingSyncTask(@org.springframework.context.annotation.Lazy accounting: AccountingService): RecurringTask<Void> =
        Tasks.recurring("accounting-sync", FixedDelay.of(Duration.ofMinutes(1))).execute { _, _ -> accounting.processDue() }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "accounting", description = "Pushing invoices and payments to QuickBooks Online and Xero")
class AccountingController(private val accounting: AccountingService, private val recentAuth: RecentAuth) {
    @GetMapping("/accounting")
    fun connections() = accounting.connections(Current.member())

    @PostMapping("/accounting/{kind}/connect")
    @Operation(summary = "Start connecting: returns the provider's page to authorise Honest Robin")
    fun connect(@PathVariable kind: String): Map<String, String> {
        // The account's invoices and payments go to the connected books.
        recentAuth.require()
        return mapOf("url" to accounting.connectUrl(Current.member(), kind))
    }

    @DeleteMapping("/accounting/{kind}")
    fun disconnect(@PathVariable kind: String) = accounting.disconnect(Current.member(), kind)

    @GetMapping("/accounting/{kind}/options")
    fun options(@PathVariable kind: String) = accounting.options(Current.member(), kind)

    @PatchMapping("/accounting/{kind}/mapping")
    fun mapping(@PathVariable kind: String, @RequestBody body: Mapping) = accounting.updateMapping(Current.member(), kind, body)

    @GetMapping("/accounting/{kind}/queue")
    fun queue(@PathVariable kind: String, @RequestParam(required = false) status: String?) = accounting.queue(Current.member(), kind, status)

    @PostMapping("/accounting/items/{id}/retry")
    fun retry(@PathVariable id: UUID) = accounting.retry(Current.member(), id)

    @GetMapping("/invoices/{id}/accounting")
    fun forInvoice(@PathVariable id: UUID) = accounting.forInvoice(Current.member(), id)

    @PostMapping("/invoices/{id}/accounting/push")
    fun push(@PathVariable id: UUID) = accounting.push(Current.member(), id)
}

@RestController
class AccountingCallbackController(private val accounting: AccountingService) {
    @GetMapping("/api/v1/public/accounting/{kind}/callback")
    fun callback(@PathVariable kind: String, @RequestParam params: Map<String, String>): ResponseEntity<Unit> =
        ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, accounting.callback(kind, params)).build()
}
