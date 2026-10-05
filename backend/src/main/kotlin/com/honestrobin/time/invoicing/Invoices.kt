// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import com.honestrobin.time.platform.web.dateIdCursor
import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.EXPENSE_CATEGORIES
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.INVOICE_EXPENSE_LINKS
import com.honestrobin.time.db.Tables.INVOICE_LINES
import com.honestrobin.time.db.Tables.INVOICE_TIME_LINKS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PAYMENTS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.tables.records.InvoiceLinesRecord
import com.honestrobin.time.db.tables.records.InvoicesRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ForbiddenException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Page
import com.honestrobin.time.platform.web.Patch
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.platform.web.Versioned
import com.honestrobin.time.platform.web.clampLimit
import com.honestrobin.time.time.Ref
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class InvoiceLineView(
    val id: UUID,
    val position: Int,
    val kind: String,
    val description: String?,
    val quantity: BigDecimal,
    val unitPrice: Long,
    val tax1Applies: Boolean,
    val tax2Applies: Boolean,
    val vatCategoryCode: String?,
    val vatPercent: BigDecimal?,
    val lineTotal: Long,
    val project: Ref?,
    val timeEntryCount: Int,
    val expenseCount: Int,
)

data class PaymentView(val id: UUID, val amount: Long, val paidDate: LocalDate, val method: String, val notes: String?, val recordedBy: String?, val externalId: String?, val createdAt: Instant)

data class InvoiceSummaryView(
    val id: UUID,
    val number: String?,
    val state: String,
    val client: Ref,
    val currency: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val subject: String?,
    val total: Long,
    val due: Long,
    val isOverdue: Boolean,
    val source: String,
    val sentAt: Instant?,
    val paidAt: Instant?,
)

data class InvoiceView(
    val id: UUID,
    val number: String?,
    val state: String,
    val client: Ref,
    val currency: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val paymentTermsDays: Int?,
    val subject: String?,
    val notes: String?,
    val purchaseOrder: String?,
    val buyerReference: String?,
    val tax1Name: String?,
    val tax1Percent: BigDecimal?,
    val tax2Name: String?,
    val tax2Percent: BigDecimal?,
    /** Null for named taxes; standard, reverse_charge, exempt or outside_scope for VAT categories per line. */
    val vatMode: String?,
    val exemptionReason: String?,
    val discountPercent: BigDecimal?,
    val subtotal: Long,
    val discount: Long,
    val tax1: Long,
    val tax2: Long,
    val total: Long,
    val paid: Long,
    val due: Long,
    val isOverdue: Boolean,
    val vatBreakdown: List<VatGroup>,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val paymentInstructions: String?,
    val footer: String?,
    val grouping: String?,
    val source: String,
    val isReadOnly: Boolean,
    val sentAt: Instant?,
    val sentTo: List<String>,
    val paidAt: Instant?,
    val voidedAt: Instant?,
    val publicUrl: String?,
    val viewCount: Int,
    val lines: List<InvoiceLineView>,
    val payments: List<PaymentView>,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class InvoiceLineInput(
    /** Keep an existing line (and the time and expenses linked to it); omit for a new line. */
    val id: UUID? = null,
    val kind: String? = null,
    val description: String? = null,
    val quantity: BigDecimal? = null,
    val unitPrice: Long? = null,
    val tax1Applies: Boolean? = null,
    val tax2Applies: Boolean? = null,
    val vatCategoryCode: String? = null,
    val vatPercent: BigDecimal? = null,
    val projectId: UUID? = null,
)

data class FromTimeInput(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val projectIds: List<UUID>? = null,
    /** project, task, person or detailed. */
    val grouping: String = "project",
    val includeExpenses: Boolean = true,
)

data class InvoiceInput(
    val clientId: UUID? = null,
    val issueDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val paymentTermsDays: Int? = null,
    val subject: String? = null,
    val notes: String? = null,
    val purchaseOrder: String? = null,
    val buyerReference: String? = null,
    val tax1Name: String? = null,
    val tax1Percent: BigDecimal? = null,
    val tax2Name: String? = null,
    val tax2Percent: BigDecimal? = null,
    val vatMode: String? = null,
    val exemptionReason: String? = null,
    val discountPercent: BigDecimal? = null,
    val paymentInstructions: String? = null,
    val footer: String? = null,
    /** The full list of lines; lines left out are removed and their time and expenses become uninvoiced. */
    val lines: List<InvoiceLineInput>? = null,
    /** Only when creating: build lines from uninvoiced billable time and expenses. */
    val fromTime: FromTimeInput? = null,
)

data class PaymentInput(val amount: Long? = null, val paidDate: LocalDate? = null, val method: String? = null, val notes: String? = null)

/** Money owed to the account, per currency, for the top of the invoice list. */
data class CurrencyTotals(val currency: String, val outstanding: Long, val overdue: Long, val outstandingCount: Int, val overdueCount: Int)
data class InvoiceSummary(val totals: List<CurrencyTotals>, val draftCount: Int)

/** What creating an invoice from time would pick up, so the form can show it first. */
data class UninvoicedProject(val project: Ref, val seconds: Long, val amount: Long, val entries: Int, val expenseAmount: Long, val expenses: Int)
data class UninvoicedPreview(val client: Ref, val currency: String, val projects: List<UninvoicedProject>, val amount: Long, val expenseAmount: Long)

val VAT_MODES = setOf("standard", "reverse_charge", "exempt", "outside_scope")
val GROUPINGS = setOf("project", "task", "person", "detailed")

/**
 * Invoices (spec §5.5): drafts built from tracked time or written by hand, numbered when they are
 * sent, paid in parts or in full, voided but never renumbered. Numbering is in [InvoiceNumbering],
 * sending and reminders in [InvoiceSender], the PDF in [InvoicePdf].
 */
@Service
class InvoiceService(
    private val dsl: DSLContext,
    private val settings: AccountSettingsRepository,
    private val numbering: InvoiceNumbering,
    private val props: HonestRobinProperties,
    private val funnel: Funnel,
    private val events: org.springframework.context.ApplicationEventPublisher,
    private val clock: Clock,
    private val recentAuth: com.honestrobin.time.platform.security.RecentAuth,
) {
    fun requireAccess(m: Member) {
        if (!m.isAdmin && !m.canManageInvoices) throw ForbiddenException("You don't have permission to manage invoices")
    }

    @Transactional(readOnly = true)
    fun list(m: Member, state: String?, clientId: UUID?, cursor: String?, limit: Int?): Page<InvoiceSummaryView> {
        requireAccess(m)
        val n = clampLimit(limit)
        var c = DSL.noCondition()
        when (state) {
            null, "all" -> {}
            "outstanding" -> c = c.and(INVOICES.STATE.`in`("sent", "open", "partially_paid"))
            "overdue" -> c = c.and(INVOICES.STATE.`in`("sent", "open", "partially_paid")).and(INVOICES.DUE_DATE.lt(today(m)))
            else -> c = c.and(INVOICES.STATE.eq(state))
        }
        clientId?.let { c = c.and(INVOICES.CLIENT_ID.eq(it)) }
        cursor?.let { cur ->
            val (date, id) = dateIdCursor(cur)
            c = c.and(DSL.row(INVOICES.ISSUE_DATE, INVOICES.ID).lt(date, id))
        }
        val rows = dsl.select(INVOICES.asterisk(), CLIENTS.NAME).from(INVOICES).join(CLIENTS).on(CLIENTS.ID.eq(INVOICES.CLIENT_ID))
            .where(c).orderBy(INVOICES.ISSUE_DATE.desc(), INVOICES.ID.desc()).limit(n + 1)
            .fetch()
        val today = today(m)
        return Page.of(
            rows.map { r ->
                val inv = r.into(INVOICES)
                InvoiceSummaryView(
                    inv.id, inv.number, inv.state, Ref(inv.clientId, r.get(CLIENTS.NAME)), inv.currency, inv.issueDate, inv.dueDate, inv.subject,
                    inv.totalMinor, inv.dueMinor, isOverdue(inv, today), inv.source, inv.sentAt, inv.paidAt,
                )
            },
            n,
        ) { "${it.issueDate}_${it.id}" }
    }

    @Transactional(readOnly = true)
    fun summary(m: Member): InvoiceSummary {
        requireAccess(m)
        val today = today(m)
        val open = dsl.select(INVOICES.CURRENCY, INVOICES.DUE_MINOR, INVOICES.DUE_DATE).from(INVOICES)
            .where(INVOICES.STATE.`in`("sent", "open", "partially_paid")).and(INVOICES.DUE_MINOR.gt(0L)).fetch()
        val totals = open.groupBy { it.value1() }.map { (currency, rows) ->
            val overdue = rows.filter { it.value3() != null && it.value3().isBefore(today) }
            CurrencyTotals(currency, rows.sumOf { it.value2() }, overdue.sumOf { it.value2() }, rows.size, overdue.size)
        }.sortedByDescending { it.outstanding }
        return InvoiceSummary(totals, dsl.fetchCount(INVOICES, INVOICES.STATE.eq("draft")))
    }

    @Transactional(readOnly = true)
    fun get(m: Member, id: UUID): InvoiceView {
        requireAccess(m)
        return view(load(id))
    }

    @Transactional(readOnly = true)
    fun uninvoiced(m: Member, clientId: UUID, from: LocalDate?, to: LocalDate?): UninvoicedPreview {
        requireAccess(m)
        val client = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(clientId)).fetchOne() ?: throw NotFoundException("Client")
        val s = settings.get(m.accountId)
        val entries = uninvoicedEntries(clientId, FromTimeInput(from, to))
        val expenses = uninvoicedExpenses(clientId, FromTimeInput(from, to))
        val projectNames = projectNames((entries.map { it.projectId } + expenses.map { it.projectId }).toSet())
        val projects = (entries.map { it.projectId } + expenses.map { it.projectId }).distinct().map { pid ->
            val es = entries.filter { it.projectId == pid }
            val xs = expenses.filter { it.projectId == pid }
            UninvoicedProject(
                Ref(pid, projectNames[pid] ?: "?"),
                es.sumOf { Money.roundSeconds(it.seconds.toLong(), s.roundingMinutes, s.roundingMode) },
                es.sumOf { Money.forDuration(Money.roundSeconds(it.seconds.toLong(), s.roundingMinutes, s.roundingMode), it.rate) },
                es.size, xs.sumOf { it.amount }, xs.size,
            )
        }.sortedBy { it.project.name }
        return UninvoicedPreview(Ref(client.id, client.name), client.currency, projects, projects.sumOf { it.amount }, projects.sumOf { it.expenseAmount })
    }

    @Transactional
    fun create(m: Member, input: InvoiceInput): InvoiceView {
        m.requireWritable()
        requireAccess(m)
        val clientId = input.clientId ?: throw ValidationException("client_id", "Choose a client")
        val client = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(clientId)).fetchOne() ?: throw ValidationException("client_id", "Choose a client")
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!!
        val issueDate = input.issueDate ?: today(m)
        val terms = input.paymentTermsDays ?: account.invoicePaymentTermsDays
        val r = dsl.newRecord(INVOICES).apply {
            accountId = m.accountId
            this.clientId = clientId
            this.issueDate = issueDate
            paymentTermsDays = terms
            dueDate = input.dueDate ?: issueDate.plusDays(terms.toLong())
            currency = client.currency
            state = "draft"
            subject = input.subject
            notes = input.notes ?: account.invoiceNotes
            purchaseOrder = input.purchaseOrder
            buyerReference = input.buyerReference
            tax1Name = input.tax1Name ?: account.defaultTax1Name
            tax1Percent = input.tax1Percent ?: account.defaultTax1Percent
            tax2Name = input.tax2Name ?: account.defaultTax2Name
            tax2Percent = input.tax2Percent ?: account.defaultTax2Percent
            vatMode = input.vatMode
            exemptionReason = input.exemptionReason
            discountPercent = input.discountPercent
            val accountDefault = account.paymentInstructions ?: defaultPaymentInstructions(account.iban, account.bic)
            if (input.paymentInstructions != null && input.paymentInstructions != accountDefault) requireMayRedirectPayments(m)
            paymentInstructions = input.paymentInstructions ?: accountDefault
            footer = input.footer ?: account.invoiceFooter
            source = "native"
            createdBy = m.membershipId
        }
        validateHeader(r)
        r.store()
        var position = 0
        input.fromTime?.let { position = addFromTime(m, r, it) }
        input.lines?.forEach { line -> insertLine(r, line, position++) }
        recompute(r)
        return view(load(r.id))
    }

    /**
     * An invoice's own payment instructions tell its client where to pay, so changing them is held
     * to the same rule as the account's: an admin, in the web app, who signed in recently.
     */
    private fun requireMayRedirectPayments(m: Member) {
        m.requireAdmin()
        recentAuth.require()
    }

    @Transactional
    fun update(m: Member, id: UUID, patch: Patch<InvoiceInput>): InvoiceView {
        m.requireWritable()
        requireAccess(m)
        val r = load(id)
        requireEditable(r)
        val i = patch.value
        patch.field("client_id", { clientId }) {
            if (it != r.clientId) {
                if (r.state != "draft") throw ConflictException("client_locked", "The client of a sent invoice can't change")
                if (dsl.fetchExists(INVOICE_TIME_LINKS.join(INVOICE_LINES).on(INVOICE_LINES.ID.eq(INVOICE_TIME_LINKS.INVOICE_LINE_ID)), INVOICE_LINES.INVOICE_ID.eq(id))) {
                    throw ConflictException("client_locked", "Remove the lines with tracked time before changing the client")
                }
                val client = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(it ?: throw ValidationException("client_id", "Choose a client"))).fetchOne()
                    ?: throw ValidationException("client_id", "Choose a client")
                r.clientId = client.id
                r.currency = client.currency
            }
        }
        patch.field("issue_date", { issueDate }) { r.issueDate = it ?: throw ValidationException("issue_date", "Enter the issue date") }
        patch.field("payment_terms_days", { paymentTermsDays }) {
            r.paymentTermsDays = it
            if (!patch.has("due_date") && it != null) r.dueDate = r.issueDate.plusDays(it.toLong())
        }
        patch.field("due_date", { dueDate }) { r.dueDate = it }
        patch.field("subject", { subject }) { r.subject = it }
        patch.field("notes", { notes }) { r.notes = it }
        patch.field("purchase_order", { purchaseOrder }) { r.purchaseOrder = it }
        patch.field("buyer_reference", { buyerReference }) { r.buyerReference = it }
        patch.field("tax1_name", { tax1Name }) { r.tax1Name = it }
        patch.field("tax1_percent", { tax1Percent }) { r.tax1Percent = it }
        patch.field("tax2_name", { tax2Name }) { r.tax2Name = it }
        patch.field("tax2_percent", { tax2Percent }) { r.tax2Percent = it }
        patch.field("vat_mode", { vatMode }) { r.vatMode = it }
        patch.field("exemption_reason", { exemptionReason }) { r.exemptionReason = it }
        patch.field("discount_percent", { discountPercent }) { r.discountPercent = it }
        patch.field("payment_instructions", { paymentInstructions }) {
            if (it != r.paymentInstructions) requireMayRedirectPayments(m)
            r.paymentInstructions = it
        }
        patch.field("footer", { footer }) { r.footer = it }
        validateHeader(r)
        r.store()
        patch.field("lines", { lines }) { replaceLines(r, it ?: emptyList()) }
        recompute(r)
        if (r.totalMinor < r.paidMinor) throw ValidationException(mapOf("lines" to "The total can't be less than what has already been paid"))
        return view(load(id))
    }

    /** Only drafts are deleted; a numbered invoice is voided instead so its number stays accounted for. */
    @Transactional
    fun delete(m: Member, id: UUID) {
        m.requireWritable()
        requireAccess(m)
        val r = load(id)
        if (r.isReadOnly) throw ConflictException("read_only", "Imported invoices can't be deleted")
        if (r.number != null) throw ConflictException("numbered", "A sent invoice can't be deleted. Void it instead, so its number stays accounted for.")
        unlinkAll(r.id)
        r.delete()
    }

    @Transactional
    fun markSent(m: Member, id: UUID): InvoiceView {
        m.requireWritable()
        requireAccess(m)
        val r = load(id)
        requireEditable(r)
        issue(r)
        r.store()
        return view(load(id))
    }

    /** Numbers a draft and moves it to sent; called when it is sent or marked as sent. */
    fun issue(r: InvoicesRecord) {
        if (r.totalMinor <= 0 && !dsl.fetchExists(INVOICE_LINES, INVOICE_LINES.INVOICE_ID.eq(r.id))) {
            throw ConflictException("empty", "Add at least one line before sending")
        }
        if (r.number == null) numbering.assign(r)
        if (r.state == "draft") r.state = "sent"
        if (r.sentAt == null) {
            r.sentAt = Instant.now(clock)
            funnel.milestone(r.accountId, Funnel.FIRST_INVOICE_SENT)
            events.publishEvent(InvoiceIssued(r.accountId, r.id))
        }
        if (r.publicToken == null) r.publicToken = Tokens.generate(24)
    }

    @Transactional
    fun void(m: Member, id: UUID): InvoiceView {
        m.requireWritable()
        requireAccess(m)
        val r = load(id)
        if (r.isReadOnly) throw ConflictException("read_only", "Imported invoices can't be changed")
        if (r.state == "void") throw ConflictException("already_void", "This invoice is already void")
        if (r.paidMinor > 0) throw ConflictException("has_payments", "An invoice with payments can't be voided. Delete the payments first.")
        if (r.number == null) throw ConflictException("draft", "Delete the draft instead")
        unlinkAll(r.id)
        r.state = "void"
        r.voidedAt = Instant.now(clock)
        r.store()
        return view(load(id))
    }

    @Transactional
    fun addPayment(m: Member, id: UUID, input: PaymentInput): InvoiceView {
        m.requireWritable()
        requireAccess(m)
        val r = load(id)
        if (r.isReadOnly) throw ConflictException("read_only", "Imported invoices can't be changed")
        if (r.state !in setOf("sent", "open", "partially_paid")) throw ConflictException("not_payable", "Only a sent invoice can be paid")
        val amount = input.amount ?: throw ValidationException("amount", "Enter the amount paid")
        if (amount <= 0) throw ValidationException("amount", "Enter an amount above zero")
        if (amount > r.dueMinor) throw ValidationException("amount", "That is more than the amount due")
        val method = input.method ?: "manual"
        if (method !in setOf("manual", "other")) throw ValidationException("method", "Use manual or other")
        val paymentId = dsl.insertInto(PAYMENTS).set(PAYMENTS.ACCOUNT_ID, m.accountId).set(PAYMENTS.INVOICE_ID, id).set(PAYMENTS.AMOUNT_MINOR, amount)
            .set(PAYMENTS.PAID_DATE, input.paidDate ?: today(m)).set(PAYMENTS.PAID_AT, Instant.now(clock)).set(PAYMENTS.METHOD, method)
            .set(PAYMENTS.NOTES, input.notes).set(PAYMENTS.RECORDED_BY, m.name).returning(PAYMENTS.ID).fetchOne()!!.id
        settle(r)
        events.publishEvent(PaymentRecorded(m.accountId, id, paymentId))
        return view(load(id))
    }

    @Transactional
    fun deletePayment(m: Member, id: UUID, paymentId: UUID): InvoiceView {
        m.requireWritable()
        requireAccess(m)
        val r = load(id)
        if (r.isReadOnly) throw ConflictException("read_only", "Imported invoices can't be changed")
        val deleted = dsl.deleteFrom(PAYMENTS).where(PAYMENTS.ID.eq(paymentId)).and(PAYMENTS.INVOICE_ID.eq(id)).execute()
        if (deleted == 0) throw NotFoundException("Payment")
        settle(r)
        return view(load(id))
    }

    /** Recomputes paid and due from the payments, and the state from those. */
    fun settle(r: InvoicesRecord) {
        val paid = dsl.select(DSL.coalesce(DSL.sum(PAYMENTS.AMOUNT_MINOR), BigDecimal.ZERO)).from(PAYMENTS).where(PAYMENTS.INVOICE_ID.eq(r.id)).fetchOne()!!.value1().toLong()
        r.paidMinor = paid
        r.dueMinor = (r.totalMinor - paid).coerceAtLeast(0)
        if (r.state != "void" && r.state != "draft") {
            r.state = when {
                r.dueMinor == 0L && r.totalMinor > 0 -> "paid"
                paid > 0 -> "partially_paid"
                else -> "sent"
            }
            r.paidAt = if (r.state == "paid") r.paidAt ?: Instant.now(clock) else null
        }
        r.store()
    }

    // ------------------------------------------------------------------ lines

    private data class Entry(val id: UUID, val projectId: UUID, val taskId: UUID, val membershipId: UUID, val date: LocalDate, val seconds: Int, val rate: Long, val notes: String?)
    private data class Expense(val id: UUID, val projectId: UUID, val categoryId: UUID, val date: LocalDate, val amount: Long, val units: BigDecimal?, val notes: String?)

    private fun uninvoicedEntries(clientId: UUID, f: FromTimeInput): List<Entry> {
        var c = PROJECTS.CLIENT_ID.eq(clientId).and(TIME_ENTRIES.INVOICE_ID.isNull).and(TIME_ENTRIES.BILLABLE.isTrue)
            .and(TIME_ENTRIES.TIMER_STARTED_AT.isNull).and(TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT.gt(0L)).and(PROJECTS.IS_BILLABLE.isTrue)
        f.from?.let { c = c.and(TIME_ENTRIES.SPENT_DATE.ge(it)) }
        f.to?.let { c = c.and(TIME_ENTRIES.SPENT_DATE.le(it)) }
        f.projectIds?.takeIf { it.isNotEmpty() }?.let { c = c.and(TIME_ENTRIES.PROJECT_ID.`in`(it)) }
        return dsl.select(TIME_ENTRIES.ID, TIME_ENTRIES.PROJECT_ID, TIME_ENTRIES.TASK_ID, TIME_ENTRIES.MEMBERSHIP_ID, TIME_ENTRIES.SPENT_DATE, TIME_ENTRIES.DURATION_SECONDS, TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT, TIME_ENTRIES.NOTES)
            .from(TIME_ENTRIES).join(PROJECTS).on(PROJECTS.ID.eq(TIME_ENTRIES.PROJECT_ID))
            .where(c).orderBy(TIME_ENTRIES.SPENT_DATE, TIME_ENTRIES.CREATED_AT)
            .fetch { Entry(it.value1(), it.value2(), it.value3(), it.value4(), it.value5(), it.value6(), it.value7(), it.value8()) }
    }

    private fun uninvoicedExpenses(clientId: UUID, f: FromTimeInput): List<Expense> {
        var c = PROJECTS.CLIENT_ID.eq(clientId).and(EXPENSES.INVOICE_ID.isNull).and(EXPENSES.BILLABLE.isTrue)
        f.from?.let { c = c.and(EXPENSES.SPENT_DATE.ge(it)) }
        f.to?.let { c = c.and(EXPENSES.SPENT_DATE.le(it)) }
        f.projectIds?.takeIf { it.isNotEmpty() }?.let { c = c.and(EXPENSES.PROJECT_ID.`in`(it)) }
        return dsl.select(EXPENSES.ID, EXPENSES.PROJECT_ID, EXPENSES.CATEGORY_ID, EXPENSES.SPENT_DATE, EXPENSES.AMOUNT_MINOR, EXPENSES.UNITS, EXPENSES.NOTES)
            .from(EXPENSES).join(PROJECTS).on(PROJECTS.ID.eq(EXPENSES.PROJECT_ID))
            .where(c).orderBy(EXPENSES.SPENT_DATE, EXPENSES.CREATED_AT)
            .fetch { Expense(it.value1(), it.value2(), it.value3(), it.value4(), it.value5(), it.value6(), it.value7()) }
    }

    private fun projectNames(ids: Set<UUID>) =
        if (ids.isEmpty()) emptyMap() else dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.CODE).from(PROJECTS).where(PROJECTS.ID.`in`(ids))
            .fetch().associate { it.value1() to (if (it.value3().isNullOrBlank()) it.value2() else "[${it.value3()}] ${it.value2()}") }

    /** Builds lines from uninvoiced billable time (and expenses) and links them; returns the next position. */
    private fun addFromTime(m: Member, invoice: InvoicesRecord, f: FromTimeInput): Int {
        if (f.grouping !in GROUPINGS) throw ValidationException("from_time.grouping", "Group by project, task, person or entry")
        val s = settings.get(m.accountId)
        val entries = uninvoicedEntries(invoice.clientId, f)
        val expenses = if (f.includeExpenses) uninvoicedExpenses(invoice.clientId, f) else emptyList()
        if (entries.isEmpty() && expenses.isEmpty()) throw ValidationException("from_time", "There is no uninvoiced billable time or expense for this client in that period")
        val projects = projectNames((entries.map { it.projectId } + expenses.map { it.projectId }).toSet())
        val tasks = entries.map { it.taskId }.toSet().takeIf { it.isNotEmpty() }?.let { ids -> dsl.select(TASKS.ID, TASKS.NAME).from(TASKS).where(TASKS.ID.`in`(ids)).fetchMap(TASKS.ID, TASKS.NAME) }.orEmpty()
        val people = entries.map { it.membershipId }.toSet().takeIf { it.isNotEmpty() }?.let { ids -> dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.`in`(ids)).fetchMap(MEMBERSHIPS.ID, MEMBERSHIPS.NAME) }.orEmpty()
        val categories = expenses.map { it.categoryId }.toSet().takeIf { it.isNotEmpty() }?.let { ids -> dsl.select(EXPENSE_CATEGORIES.ID, EXPENSE_CATEGORIES.NAME).from(EXPENSE_CATEGORIES).where(EXPENSE_CATEGORIES.ID.`in`(ids)).fetchMap(EXPENSE_CATEGORIES.ID, EXPENSE_CATEGORIES.NAME) }.orEmpty()

        // Claim the entries first, so two drafts can never take the same time.
        claim(TIME_ENTRIES.ID, TIME_ENTRIES.INVOICE_ID, entries.map { it.id }, invoice.id)
        claim(EXPENSES.ID, EXPENSES.INVOICE_ID, expenses.map { it.id }, invoice.id)

        var position = 0
        val tax1 = invoice.tax1Percent != null
        val tax2 = invoice.tax2Percent != null
        val groups: Map<List<Any?>, List<Entry>> = entries.groupBy { e ->
            when (f.grouping) {
                "task" -> listOf(e.projectId, e.taskId, e.rate)
                "person" -> listOf(e.projectId, e.membershipId, e.rate)
                "detailed" -> listOf(e.id)
                else -> listOf(e.projectId, e.rate)
            }
        }
        // Lines read in order: by project, then task or person, then date for single entries.
        groups.values.sortedWith(compareBy({ projects[it.first().projectId] }, { tasks[it.first().taskId].takeIf { f.grouping == "task" } }, { people[it.first().membershipId].takeIf { f.grouping == "person" } }, { it.first().date })).forEach { es ->
            val first = es.first()
            val seconds = es.sumOf { Money.roundSeconds(it.seconds.toLong(), s.roundingMinutes, s.roundingMode) }
            val description = when (f.grouping) {
                "task" -> "${projects[first.projectId]} — ${tasks[first.taskId]}"
                "person" -> "${projects[first.projectId]} — ${people[first.membershipId]}"
                "detailed" -> listOfNotNull("${first.date} · ${people[first.membershipId]} · ${projects[first.projectId]} — ${tasks[first.taskId]}", first.notes?.takeIf { it.isNotBlank() }).joinToString("\n")
                else -> projects[first.projectId] ?: "Services"
            }
            val line = newLine(invoice, position++, "service", description, hours(seconds), first.rate, tax1, tax2, first.projectId)
            line.taskId = if (f.grouping in setOf("task", "detailed")) first.taskId else null
            line.membershipId = if (f.grouping in setOf("person", "detailed")) first.membershipId else null
            line.store()
            es.forEach { e ->
                dsl.insertInto(INVOICE_TIME_LINKS).set(INVOICE_TIME_LINKS.ACCOUNT_ID, invoice.accountId).set(INVOICE_TIME_LINKS.INVOICE_LINE_ID, line.id).set(INVOICE_TIME_LINKS.TIME_ENTRY_ID, e.id).execute()
            }
        }
        val expenseGroups = expenses.groupBy { x -> if (f.grouping == "detailed") listOf(x.id) else listOf(x.projectId, x.categoryId) }
        expenseGroups.values.forEach { xs ->
            val first = xs.first()
            val description = if (f.grouping == "detailed") {
                listOfNotNull("${first.date} · ${projects[first.projectId]} — ${categories[first.categoryId]}", first.notes?.takeIf { it.isNotBlank() }).joinToString("\n")
            } else {
                "${projects[first.projectId]} — ${categories[first.categoryId]}"
            }
            val line = newLine(invoice, position++, "expense", description, BigDecimal.ONE, xs.sumOf { it.amount }, tax1, tax2, first.projectId)
            line.store()
            xs.forEach { x ->
                dsl.insertInto(INVOICE_EXPENSE_LINKS).set(INVOICE_EXPENSE_LINKS.ACCOUNT_ID, invoice.accountId).set(INVOICE_EXPENSE_LINKS.INVOICE_LINE_ID, line.id).set(INVOICE_EXPENSE_LINKS.EXPENSE_ID, x.id).execute()
            }
        }
        invoice.grouping = f.grouping
        invoice.periodStart = (entries.map { it.date } + expenses.map { it.date }).minOrNull()
        invoice.periodEnd = (entries.map { it.date } + expenses.map { it.date }).maxOrNull()
        invoice.store()
        return position
    }

    private fun <R : org.jooq.Record> claim(idField: org.jooq.TableField<R, UUID>, invoiceField: org.jooq.TableField<R, UUID>, ids: List<UUID>, invoiceId: UUID) {
        if (ids.isEmpty()) return
        val claimed = dsl.update(idField.table!!).set(invoiceField, invoiceId).where(idField.`in`(ids)).and(invoiceField.isNull).execute()
        if (claimed != ids.size) throw ConflictException("entries_changed", "Some of that time was invoiced or changed meanwhile. Try again.")
    }

    private fun hours(seconds: Long): BigDecimal = BigDecimal.valueOf(seconds).divide(BigDecimal(3600), 4, RoundingMode.HALF_UP)

    private fun newLine(invoice: InvoicesRecord, position: Int, kind: String, description: String?, quantity: BigDecimal, unitPrice: Long, tax1: Boolean, tax2: Boolean, projectId: UUID?): InvoiceLinesRecord =
        dsl.newRecord(INVOICE_LINES).apply {
            accountId = invoice.accountId
            invoiceId = invoice.id
            this.position = position
            this.kind = kind
            this.description = description
            this.quantity = quantity
            this.unitPriceMinor = unitPrice
            tax1Applies = tax1
            tax2Applies = tax2
            this.projectId = projectId
            if (invoice.vatMode != null) {
                vatCategoryCode = defaultCategory(invoice.vatMode)
                vatPercent = if (vatCategoryCode == "S") invoice.tax1Percent ?: BigDecimal.ZERO else BigDecimal.ZERO
            }
            lineTotalMinor = InvoiceMath.lineTotal(quantity, unitPrice)
        }

    private fun defaultCategory(vatMode: String?) = when (vatMode) {
        "reverse_charge" -> "AE"
        "exempt" -> "E"
        "outside_scope" -> "O"
        else -> "S"
    }

    private fun insertLine(invoice: InvoicesRecord, input: InvoiceLineInput, position: Int) {
        val line = newLine(invoice, position, input.kind ?: "service", input.description, input.quantity ?: BigDecimal.ONE, input.unitPrice ?: 0, input.tax1Applies ?: (invoice.tax1Percent != null), input.tax2Applies ?: (invoice.tax2Percent != null), input.projectId)
        applyLine(invoice, line, input)
        line.store()
    }

    private fun applyLine(invoice: InvoicesRecord, line: InvoiceLinesRecord, input: InvoiceLineInput) {
        val kind = input.kind ?: line.kind
        if (kind !in setOf("service", "product", "expense")) throw ValidationException("lines", "A line is a service, a product or an expense")
        line.kind = kind
        input.description?.let { line.description = it.take(5000) }
        input.quantity?.let { line.quantity = it.setScale(4, RoundingMode.HALF_UP) }
        input.unitPrice?.let { line.unitPriceMinor = it }
        input.tax1Applies?.let { line.tax1Applies = it }
        input.tax2Applies?.let { line.tax2Applies = it }
        input.projectId?.let {
            if (!dsl.fetchExists(PROJECTS, PROJECTS.ID.eq(it))) throw ValidationException("lines", "Unknown project")
            line.projectId = it
        }
        if (invoice.vatMode != null) {
            val category = input.vatCategoryCode ?: line.vatCategoryCode ?: defaultCategory(invoice.vatMode)
            if (category !in InvoiceMath.VAT_CATEGORIES) throw ValidationException("lines", "Unknown VAT category $category")
            line.vatCategoryCode = category
            line.vatPercent = if (InvoiceMath.VAT_CATEGORIES.getValue(category)) (input.vatPercent ?: line.vatPercent ?: BigDecimal.ZERO) else BigDecimal.ZERO
            if (line.vatPercent < BigDecimal.ZERO || line.vatPercent > BigDecimal(100)) throw ValidationException("lines", "A VAT rate is between 0 and 100%")
        }
        if (line.quantity.abs() > BigDecimal(1_000_000_000)) throw ValidationException("lines", "That quantity is too large")
        line.lineTotalMinor = InvoiceMath.lineTotal(line.quantity, line.unitPriceMinor)
    }

    private fun replaceLines(invoice: InvoicesRecord, lines: List<InvoiceLineInput>) {
        val existing = dsl.selectFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(invoice.id)).fetch().associateBy { it.id }
        val kept = lines.mapNotNull { it.id }.toSet()
        (kept - existing.keys).firstOrNull()?.let { throw ValidationException("lines", "Line $it is not on this invoice") }
        val removed = existing.keys - kept
        if (removed.isNotEmpty()) {
            unlinkLines(removed)
            dsl.deleteFrom(INVOICE_LINES).where(INVOICE_LINES.ID.`in`(removed)).execute()
        }
        lines.forEachIndexed { position, input ->
            val line = input.id?.let(existing::get)
            if (line == null) {
                insertLine(invoice, input, position)
            } else {
                line.position = position
                applyLine(invoice, line, input)
                line.store()
            }
        }
    }

    private fun unlinkLines(lineIds: Collection<UUID>) {
        val entries = dsl.select(INVOICE_TIME_LINKS.TIME_ENTRY_ID).from(INVOICE_TIME_LINKS).where(INVOICE_TIME_LINKS.INVOICE_LINE_ID.`in`(lineIds)).fetch(INVOICE_TIME_LINKS.TIME_ENTRY_ID)
        if (entries.isNotEmpty()) dsl.update(TIME_ENTRIES).set(TIME_ENTRIES.INVOICE_ID, null as UUID?).where(TIME_ENTRIES.ID.`in`(entries)).execute()
        val expenses = dsl.select(INVOICE_EXPENSE_LINKS.EXPENSE_ID).from(INVOICE_EXPENSE_LINKS).where(INVOICE_EXPENSE_LINKS.INVOICE_LINE_ID.`in`(lineIds)).fetch(INVOICE_EXPENSE_LINKS.EXPENSE_ID)
        if (expenses.isNotEmpty()) dsl.update(EXPENSES).set(EXPENSES.INVOICE_ID, null as UUID?).where(EXPENSES.ID.`in`(expenses)).execute()
        dsl.deleteFrom(INVOICE_TIME_LINKS).where(INVOICE_TIME_LINKS.INVOICE_LINE_ID.`in`(lineIds)).execute()
        dsl.deleteFrom(INVOICE_EXPENSE_LINKS).where(INVOICE_EXPENSE_LINKS.INVOICE_LINE_ID.`in`(lineIds)).execute()
    }

    /** Frees every entry and expense on the invoice; they become uninvoiced again (spec §5.5). */
    private fun unlinkAll(invoiceId: UUID) {
        unlinkLines(dsl.select(INVOICE_LINES.ID).from(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(invoiceId)).fetch(INVOICE_LINES.ID))
        dsl.update(TIME_ENTRIES).set(TIME_ENTRIES.INVOICE_ID, null as UUID?).where(TIME_ENTRIES.INVOICE_ID.eq(invoiceId)).execute()
        dsl.update(EXPENSES).set(EXPENSES.INVOICE_ID, null as UUID?).where(EXPENSES.INVOICE_ID.eq(invoiceId)).execute()
    }

    // ------------------------------------------------------------------ totals and checks

    fun totals(r: InvoicesRecord, lines: List<InvoiceLinesRecord>): Totals = InvoiceMath.totals(
        lines.map { MathLine(it.quantity, it.unitPriceMinor, it.tax1Applies, it.tax2Applies, it.vatCategoryCode, it.vatPercent) },
        r.discountPercent, r.tax1Percent, r.tax2Percent, r.vatMode != null,
    )

    private fun recompute(r: InvoicesRecord) {
        val lines = dsl.selectFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(r.id)).orderBy(INVOICE_LINES.POSITION).fetch()
        val t = totals(r, lines)
        if (t.total < 0) throw ValidationException(mapOf("lines" to "The total can't be below zero"))
        r.subtotalMinor = t.subtotal
        r.discountMinor = t.discount
        r.tax1Minor = t.tax1
        r.tax2Minor = t.tax2
        r.totalMinor = t.total
        r.store()
        settle(r)
    }

    private fun validateHeader(r: InvoicesRecord) {
        val errors = mutableMapOf<String, String>()
        r.vatMode?.let { if (it !in VAT_MODES) errors["vat_mode"] = "Use standard, reverse_charge, exempt or outside_scope" }
        listOf("tax1_percent" to r.tax1Percent, "tax2_percent" to r.tax2Percent, "discount_percent" to r.discountPercent).forEach { (field, value) ->
            if (value != null && (value < BigDecimal.ZERO || value > BigDecimal(100))) errors[field] = "Enter a percentage from 0 to 100"
        }
        if (r.tax1Percent != null && r.tax1Name.isNullOrBlank() && r.vatMode == null) errors["tax1_name"] = "Name the tax, e.g. VAT, GST or Sales tax"
        if (r.tax2Percent != null && r.tax2Name.isNullOrBlank()) errors["tax2_name"] = "Name the second tax"
        if (r.dueDate != null && r.dueDate.isBefore(r.issueDate)) errors["due_date"] = "The due date can't be before the issue date"
        r.paymentTermsDays?.let { if (it !in 0..365) errors["payment_terms_days"] = "Use 0 to 365 days" }
        if (r.vatMode == "reverse_charge") {
            val vatId = dsl.select(CLIENTS.VAT_ID).from(CLIENTS).where(CLIENTS.ID.eq(r.clientId)).fetchOne()?.value1()
            if (vatId.isNullOrBlank()) errors["vat_mode"] = "Reverse charge needs the client's tax ID. Add it to the client first."
        }
        if (r.vatMode == "exempt" && r.exemptionReason.isNullOrBlank()) errors["exemption_reason"] = "Say why the invoice is exempt, e.g. the law that exempts it"
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    private fun requireEditable(r: InvoicesRecord) {
        if (r.isReadOnly) throw ConflictException("read_only", "Imported invoices are read-only")
        if (r.state == "paid") throw ConflictException("paid", "A paid invoice can't be changed")
        if (r.state == "void") throw ConflictException("void", "A void invoice can't be changed")
    }

    fun load(id: UUID): InvoicesRecord = dsl.selectFrom(INVOICES).where(INVOICES.ID.eq(id)).fetchOne() ?: throw NotFoundException("Invoice")

    private fun today(m: Member) = settings.get(m.accountId).today(clock)

    private fun isOverdue(r: InvoicesRecord, today: LocalDate) = r.state in setOf("sent", "open", "partially_paid") && r.dueDate != null && r.dueDate.isBefore(today)

    fun publicUrl(token: String?) = token?.let { "${props.baseUrl}/i/$it" }

    fun view(r: InvoicesRecord): InvoiceView {
        val lines = dsl.selectFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(r.id)).orderBy(INVOICE_LINES.POSITION).fetch()
        val lineIds = lines.map { it.id }
        val timeCounts = if (lineIds.isEmpty()) emptyMap() else dsl.select(INVOICE_TIME_LINKS.INVOICE_LINE_ID, DSL.count()).from(INVOICE_TIME_LINKS)
            .where(INVOICE_TIME_LINKS.INVOICE_LINE_ID.`in`(lineIds)).groupBy(INVOICE_TIME_LINKS.INVOICE_LINE_ID).fetchMap(INVOICE_TIME_LINKS.INVOICE_LINE_ID, DSL.count())
        val expenseCounts = if (lineIds.isEmpty()) emptyMap() else dsl.select(INVOICE_EXPENSE_LINKS.INVOICE_LINE_ID, DSL.count()).from(INVOICE_EXPENSE_LINKS)
            .where(INVOICE_EXPENSE_LINKS.INVOICE_LINE_ID.`in`(lineIds)).groupBy(INVOICE_EXPENSE_LINKS.INVOICE_LINE_ID).fetchMap(INVOICE_EXPENSE_LINKS.INVOICE_LINE_ID, DSL.count())
        val projects = projectNames(lines.mapNotNull { it.projectId }.toSet())
        val client = dsl.select(CLIENTS.NAME).from(CLIENTS).where(CLIENTS.ID.eq(r.clientId)).fetchOne()!!.value1()
        val payments = dsl.selectFrom(PAYMENTS).where(PAYMENTS.INVOICE_ID.eq(r.id)).orderBy(PAYMENTS.PAID_DATE, PAYMENTS.CREATED_AT).fetch()
            .map { PaymentView(it.id, it.amountMinor, it.paidDate, it.method, it.notes, it.recordedBy, it.externalId, it.createdAt) }
        val t = totals(r, lines)
        val today = settings.get(r.accountId).today(clock)
        return InvoiceView(
            id = r.id, number = r.number, state = r.state, client = Ref(r.clientId, client), currency = r.currency,
            issueDate = r.issueDate, dueDate = r.dueDate, paymentTermsDays = r.paymentTermsDays, subject = r.subject, notes = r.notes,
            purchaseOrder = r.purchaseOrder, buyerReference = r.buyerReference,
            tax1Name = r.tax1Name, tax1Percent = r.tax1Percent, tax2Name = r.tax2Name, tax2Percent = r.tax2Percent,
            vatMode = r.vatMode, exemptionReason = r.exemptionReason, discountPercent = r.discountPercent,
            subtotal = r.subtotalMinor, discount = r.discountMinor, tax1 = r.tax1Minor, tax2 = r.tax2Minor, total = r.totalMinor,
            paid = r.paidMinor, due = r.dueMinor, isOverdue = isOverdue(r, today), vatBreakdown = t.vat,
            periodStart = r.periodStart, periodEnd = r.periodEnd, paymentInstructions = r.paymentInstructions, footer = r.footer,
            grouping = r.grouping, source = r.source, isReadOnly = r.isReadOnly, sentAt = r.sentAt, sentTo = r.sentTo?.toList() ?: emptyList(),
            paidAt = r.paidAt, voidedAt = r.voidedAt, publicUrl = publicUrl(r.publicToken), viewCount = r.viewCount,
            lines = lines.map { l ->
                InvoiceLineView(
                    l.id, l.position, l.kind, l.description, l.quantity, l.unitPriceMinor, l.tax1Applies, l.tax2Applies, l.vatCategoryCode, l.vatPercent,
                    l.lineTotalMinor, l.projectId?.let { Ref(it, projects[it] ?: "?") }, timeCounts[l.id] ?: 0, expenseCounts[l.id] ?: 0,
                )
            },
            payments = payments, createdAt = r.createdAt, updatedAt = r.updatedAt,
        )
    }

    companion object {
        /** A starting point for payment details when only IBAN and BIC are on file. */
        fun defaultPaymentInstructions(iban: String?, bic: String?): String? =
            listOfNotNull(iban?.takeIf { it.isNotBlank() }?.let { "IBAN $it" }, bic?.takeIf { it.isNotBlank() }?.let { "BIC $it" }).joinToString("\n").ifBlank { null }
    }
}
