// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.reports

import com.honestrobin.time.platform.web.dateIdCursor
import com.honestrobin.time.accounts.Access
import com.honestrobin.time.accounts.AccountSettings
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.budgets.BudgetService
import com.honestrobin.time.budgets.BudgetView
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.EXPENSE_CATEGORIES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TEAM_MEMBERSHIPS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.expenses.ExpenseService
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.Page
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.platform.web.Views
import com.honestrobin.time.platform.web.clampLimit
import com.honestrobin.time.time.ProjectRef
import com.honestrobin.time.time.Ref
import com.honestrobin.time.time.TimeEntryService
import com.honestrobin.time.time.TimeEntryView
import com.honestrobin.time.time.EntrySql
import com.fasterxml.jackson.annotation.JsonView
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Filters shared by every report (spec §12). Lists mean "any of"; empty or null means no filter. */
data class ReportFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val clientIds: List<UUID> = emptyList(),
    val projectIds: List<UUID> = emptyList(),
    val taskIds: List<UUID> = emptyList(),
    val membershipIds: List<UUID> = emptyList(),
    val teamIds: List<UUID> = emptyList(),
    val categoryIds: List<UUID> = emptyList(),
    val billable: Boolean? = null,
    val approvalStates: List<String> = emptyList(),
    val invoiced: Boolean? = null,
) {
    fun validate() {
        if (from != null && to != null && from > to) throw ValidationException("to", "The end date is before the start date")
        approvalStates.firstOrNull { it !in APPROVAL_STATES }?.let { throw ValidationException("approval_state", "Unknown approval state: $it") }
    }

    companion object {
        val APPROVAL_STATES = setOf("draft", "submitted", "approved", "rejected")
    }
}

/** Amounts in one currency. Billable amounts are in the client's currency. */
data class CurrencyAmount(
    val currency: String,
    @JsonView(Views.Rates::class) val billableAmount: Long,
    @JsonView(Views.Rates::class) val uninvoicedAmount: Long,
)

data class TimeReportRow(
    /** The client, project, task or person this row is about. */
    val id: UUID,
    val name: String,
    /** The client's name on project rows. */
    val clientName: String?,
    val projectCode: String?,
    val seconds: Long,
    val billableSeconds: Long,
    val entryCount: Int,
    val amounts: List<CurrencyAmount>,
    /** Internal cost (people's cost rates), in the account's currency. */
    @JsonView(Views.Rates::class) val costAmount: Long,
)

data class TimeReport(
    val from: LocalDate?,
    val to: LocalDate?,
    val groupBy: String,
    /** Durations are rounded per entry with the account's rounding, like on invoices. */
    val roundingMinutes: Int,
    /** "up" or "nearest". */
    val roundingMode: String,
    @JsonView(Views.Rates::class) val costCurrency: String,
    val seconds: Long,
    val billableSeconds: Long,
    val entryCount: Int,
    val amounts: List<CurrencyAmount>,
    @JsonView(Views.Rates::class) val costAmount: Long,
    val rows: List<TimeReportRow>,
)

data class UninvoicedProjectRow(
    val project: ProjectRef,
    val seconds: Long,
    @JsonView(Views.Rates::class) val amount: Long,
    @JsonView(Views.Rates::class) val expenseAmount: Long,
    val entryCount: Int,
    val expenseCount: Int,
    val oldestDate: LocalDate?,
)

data class UninvoicedClientRow(
    val client: Ref,
    val currency: String,
    val seconds: Long,
    @JsonView(Views.Rates::class) val amount: Long,
    @JsonView(Views.Rates::class) val expenseAmount: Long,
    val oldestDate: LocalDate?,
    val projects: List<UninvoicedProjectRow>,
)

data class UninvoicedTotals(
    val currency: String,
    val seconds: Long,
    @JsonView(Views.Rates::class) val amount: Long,
    @JsonView(Views.Rates::class) val expenseAmount: Long,
)

data class UninvoicedReport(val from: LocalDate?, val to: LocalDate?, val totals: List<UninvoicedTotals>, val clients: List<UninvoicedClientRow>)

data class BudgetReportRow(val project: ProjectRef, val client: Ref, val budget: BudgetView)

data class BudgetReport(val rows: List<BudgetReportRow>)

/** What was spent: visible to whoever can see the expenses (ADR 0008). */
data class ExpenseAmount(val currency: String, val amount: Long, val billableAmount: Long, val uninvoicedAmount: Long)

data class ExpenseReportRow(val id: UUID, val name: String, val clientName: String?, val count: Int, val amounts: List<ExpenseAmount>)

data class ExpenseReport(val from: LocalDate?, val to: LocalDate?, val groupBy: String, val count: Int, val amounts: List<ExpenseAmount>, val rows: List<ExpenseReportRow>)

/**
 * Reports (spec §12). Everyone sees only the time and expenses they may see (spec §11): admins
 * everything, managers their managed projects plus their own, members their own. Rates and
 * amounts are serialised only for people who may see them.
 */
@Service
class ReportService(
    private val dsl: DSLContext,
    private val access: Access,
    private val accounts: AccountSettingsRepository,
    private val entries: TimeEntryService,
    private val expenses: ExpenseService,
    private val budgets: BudgetService,
    private val clock: Clock,
) {

    // ---- time ----------------------------------------------------------------

    @Transactional(readOnly = true)
    fun time(m: Member, groupBy: String, f: ReportFilter): TimeReport {
        f.validate()
        if (groupBy !in setOf("client", "project", "task", "person")) throw ValidationException("group_by", "Group by client, project, task or person")
        val s = accounts.get(m.accountId)
        val e = fields(s)
        // Aggregate the entries first, by the grouping column and project (a project fixes the
        // client and the currency); names come afterwards from the small tables. Joining a
        // million entries to names before grouping is what makes a report slow.
        val key: Field<UUID> = when (groupBy) {
            "task" -> TIME_ENTRIES.TASK_ID
            "person" -> TIME_ENTRIES.MEMBERSHIP_ID
            else -> TIME_ENTRIES.PROJECT_ID
        }
        val billableRounded = DSL.`when`(TIME_ENTRIES.BILLABLE.isTrue, e.rounded).otherwise(DSL.inline(0))
        val uninvoiced = DSL.`when`(TIME_ENTRIES.INVOICE_ID.isNull, e.amount).otherwise(DSL.inline(0L))
        val keys = if (key == TIME_ENTRIES.PROJECT_ID) arrayOf<Field<*>>(TIME_ENTRIES.PROJECT_ID) else arrayOf(key, TIME_ENTRIES.PROJECT_ID)
        val sums = dsl.select(
            *keys,
            DSL.sum(e.rounded), DSL.sum(billableRounded), DSL.sum(e.amount), DSL.sum(uninvoiced), DSL.sum(e.cost), DSL.count(),
        )
            .from(TIME_ENTRIES)
            .where(TIME_ENTRIES.ACCOUNT_ID.eq(m.accountId)).and(entries.visibility(m)).and(timeCondition(f))
            .groupBy(*keys)
            .fetch()
        val n = keys.size
        class Part(val project: UUID, val k: UUID, val seconds: Long, val billable: Long, val amount: Long, val uninvoiced: Long, val cost: Long, val count: Int)
        val parts = sums.map { r ->
            val project = r.get(TIME_ENTRIES.PROJECT_ID)
            Part(
                project, if (n == 1) project else r.get(key),
                (r.get(n) as BigDecimal).toLong(), (r.get(n + 1) as BigDecimal).toLong(), (r.get(n + 2) as BigDecimal).toLong(),
                (r.get(n + 3) as BigDecimal).toLong(), (r.get(n + 4) as BigDecimal).toLong(), r.get(n + 5) as Int,
            )
        }
        val projects = dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.CODE, CLIENTS.ID, CLIENTS.NAME, CLIENTS.CURRENCY)
            .from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID)).where(PROJECTS.ID.`in`(parts.map { it.project }.toSet()))
            .fetchMap(PROJECTS.ID)
        val names: Map<UUID, String> = when (groupBy) {
            "task" -> dsl.select(TASKS.ID, TASKS.NAME).from(TASKS).where(TASKS.ID.`in`(parts.map { it.k }.toSet())).fetchMap(TASKS.ID, TASKS.NAME)
            "person" -> dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.`in`(parts.map { it.k }.toSet())).fetchMap(MEMBERSHIPS.ID, MEMBERSHIPS.NAME)
            "client" -> projects.values.associate { it[CLIENTS.ID] to it[CLIENTS.NAME] }
            else -> projects.mapValues { it.value[PROJECTS.NAME] }
        }
        val groupOf: (Part) -> UUID = if (groupBy == "client") { p -> projects.getValue(p.project)[CLIENTS.ID] } else { p -> p.k }

        val grouped = parts.groupBy(groupOf).map { (id, ps) ->
            val project = projects[ps.first().project]
            TimeReportRow(
                id = id, name = names[id] ?: "?",
                clientName = if (groupBy == "project") project?.get(CLIENTS.NAME) else null,
                projectCode = if (groupBy == "project") project?.get(PROJECTS.CODE) else null,
                seconds = ps.sumOf { it.seconds }, billableSeconds = ps.sumOf { it.billable }, entryCount = ps.sumOf { it.count },
                // Currencies with nothing billable (non-billable work) are left out.
                amounts = mergeAmounts(ps.map { CurrencyAmount(projects.getValue(it.project)[CLIENTS.CURRENCY], it.amount, it.uninvoiced) })
                    .filter { it.billableAmount != 0L || it.uninvoicedAmount != 0L },
                costAmount = ps.sumOf { it.cost },
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        return TimeReport(
            from = f.from, to = f.to, groupBy = groupBy, roundingMinutes = s.roundingMinutes, roundingMode = s.roundingMode, costCurrency = s.defaultCurrency,
            seconds = grouped.sumOf { it.seconds }, billableSeconds = grouped.sumOf { it.billableSeconds }, entryCount = grouped.sumOf { it.entryCount },
            amounts = mergeAmounts(grouped.flatMap { it.amounts }), costAmount = grouped.sumOf { it.costAmount }, rows = grouped,
        )
    }

    /** Entry by entry, oldest first, with the same filters as the time report. */
    @Transactional(readOnly = true)
    fun detailed(m: Member, f: ReportFilter, cursor: String?, limit: Int?): Page<TimeEntryView> {
        f.validate()
        val n = clampLimit(limit, 200, 1000)
        val seek = cursor?.let { c ->
            val (d, id) = dateIdCursor(c)
            DSL.row(TIME_ENTRIES.SPENT_DATE, TIME_ENTRIES.ID).gt(d, id)
        } ?: DSL.noCondition()
        val rows = dsl.selectFrom(TIME_ENTRIES)
            .where(TIME_ENTRIES.ACCOUNT_ID.eq(m.accountId)).and(entries.visibility(m)).and(timeCondition(f)).and(seek)
            .orderBy(TIME_ENTRIES.SPENT_DATE, TIME_ENTRIES.ID)
            .limit(n + 1).fetch()
        return Page.of(entries.views(m.accountId, rows), n) { "${it.spentDate}_${it.id}" }
    }

    // ---- uninvoiced ------------------------------------------------------------

    /** Billable time and expenses not on an invoice yet, by client and project, as invoicing would pick them up. */
    @Transactional(readOnly = true)
    fun uninvoiced(m: Member, f: ReportFilter): UninvoicedReport {
        f.validate()
        val s = accounts.get(m.accountId)
        val rounded = EntrySql.rounded(EntrySql.seconds(), s.roundingMinutes, s.roundingMode)
        val amount = EntrySql.billableAmount(rounded)
        // The same entries "Create invoice" takes (InvoiceService.uninvoicedEntries).
        val billableProjects = DSL.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IS_BILLABLE.isTrue)
        val time = dsl.select(TIME_ENTRIES.PROJECT_ID, DSL.sum(rounded), DSL.sum(amount), DSL.count(), DSL.min(TIME_ENTRIES.SPENT_DATE))
            .from(TIME_ENTRIES)
            .where(TIME_ENTRIES.ACCOUNT_ID.eq(m.accountId)).and(entries.visibility(m)).and(timeCondition(f.copy(billable = null, invoiced = null)))
            .and(TIME_ENTRIES.INVOICE_ID.isNull).and(TIME_ENTRIES.BILLABLE.isTrue).and(TIME_ENTRIES.TIMER_STARTED_AT.isNull)
            .and(TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT.gt(0L)).and(TIME_ENTRIES.PROJECT_ID.`in`(billableProjects))
            .groupBy(TIME_ENTRIES.PROJECT_ID)
            .fetch().associate { it.value1() to it }
        val spend = dsl.select(EXPENSES.PROJECT_ID, DSL.sum(EXPENSES.AMOUNT_MINOR), DSL.count(), DSL.min(EXPENSES.SPENT_DATE))
            .from(EXPENSES)
            .where(EXPENSES.ACCOUNT_ID.eq(m.accountId)).and(expenses.visibility(m)).and(expenseCondition(f.copy(billable = null, invoiced = null, taskIds = emptyList())))
            .and(EXPENSES.INVOICE_ID.isNull).and(EXPENSES.BILLABLE.isTrue)
            .groupBy(EXPENSES.PROJECT_ID)
            .fetch().associate { it.value1() to it }
        val projectIds = time.keys + spend.keys
        if (projectIds.isEmpty()) return UninvoicedReport(f.from, f.to, emptyList(), emptyList())
        val projects = dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.CODE, CLIENTS.ID, CLIENTS.NAME, CLIENTS.CURRENCY)
            .from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID)).where(PROJECTS.ID.`in`(projectIds)).fetch()

        val clients = projects.groupBy { it[CLIENTS.ID] }.map { (clientId, ps) ->
            val rows = ps.map { p ->
                val t = time[p[PROJECTS.ID]]
                val x = spend[p[PROJECTS.ID]]
                UninvoicedProjectRow(
                    project = ProjectRef(p[PROJECTS.ID], p[PROJECTS.NAME], p[PROJECTS.CODE]),
                    seconds = t?.value2()?.toLong() ?: 0, amount = t?.value3()?.toLong() ?: 0,
                    expenseAmount = x?.value2()?.toLong() ?: 0, entryCount = t?.value4() ?: 0, expenseCount = x?.value3() ?: 0,
                    oldestDate = listOfNotNull(t?.value5(), x?.value4()).minOrNull(),
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.project.name })
            UninvoicedClientRow(
                client = Ref(clientId, ps.first()[CLIENTS.NAME]), currency = ps.first()[CLIENTS.CURRENCY],
                seconds = rows.sumOf { it.seconds }, amount = rows.sumOf { it.amount }, expenseAmount = rows.sumOf { it.expenseAmount },
                oldestDate = rows.mapNotNull { it.oldestDate }.minOrNull(), projects = rows,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.client.name })
        val totals = clients.groupBy { it.currency }.map { (currency, cs) ->
            UninvoicedTotals(currency, cs.sumOf { it.seconds }, cs.sumOf { it.amount }, cs.sumOf { it.expenseAmount })
        }.sortedBy { it.currency }
        return UninvoicedReport(f.from, f.to, totals, clients)
    }

    // ---- budgets -------------------------------------------------------------

    /** Budget, spent, remaining and % used for each project with a budget the caller may see. */
    @Transactional(readOnly = true)
    fun budget(m: Member, clientIds: List<UUID>, includeArchived: Boolean): BudgetReport {
        var c: Condition = PROJECTS.BUDGET_BY.ne("none")
        if (!includeArchived) c = c.and(PROJECTS.ARCHIVED_AT.isNull)
        if (clientIds.isNotEmpty()) c = c.and(PROJECTS.CLIENT_ID.`in`(clientIds))
        val managed = access.managedProjectIds(m)
        if (managed != null) {
            // Like BudgetService.forMember: managed projects, or time budgets shown to everyone on the project.
            val assigned = DSL.select(PROJECT_MEMBERS.PROJECT_ID).from(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(m.membershipId)).and(PROJECT_MEMBERS.IS_ACTIVE.isTrue)
            c = c.and(
                PROJECTS.ID.`in`(managed).or(
                    PROJECTS.SHOW_BUDGET_TO_ALL.isTrue.and(PROJECTS.ID.`in`(assigned)).and(PROJECTS.BUDGET_BY.notIn("project_cost", "task_fees")),
                ),
            )
        }
        val projects = dsl.selectFrom(PROJECTS).where(c).fetch()
        val clients = dsl.select(CLIENTS.ID, CLIENTS.NAME).from(CLIENTS).where(CLIENTS.ID.`in`(projects.map { it.clientId }.toSet()))
            .fetchMap(CLIENTS.ID, CLIENTS.NAME)
        val usage = budgets.computeMany(projects)
        val rows = projects.map { p -> BudgetReportRow(ProjectRef(p.id, p.name, p.code), Ref(p.clientId, clients[p.clientId] ?: "?"), usage.getValue(p.id)) }.sortedWith(compareBy<BudgetReportRow, String>(String.CASE_INSENSITIVE_ORDER) { it.client.name }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.project.name })
        return BudgetReport(rows)
    }

    // ---- expenses --------------------------------------------------------------

    @Transactional(readOnly = true)
    fun expenses(m: Member, groupBy: String, f: ReportFilter): ExpenseReport {
        f.validate()
        val key: Field<UUID> = when (groupBy) {
            "category" -> EXPENSES.CATEGORY_ID
            "client" -> PROJECTS.CLIENT_ID
            "project" -> EXPENSES.PROJECT_ID
            "person" -> EXPENSES.MEMBERSHIP_ID
            else -> throw ValidationException("group_by", "Group by category, client, project or person")
        }
        val name: Field<String> = when (groupBy) {
            "category" -> EXPENSE_CATEGORIES.NAME
            "client" -> CLIENTS.NAME
            "project" -> PROJECTS.NAME
            else -> MEMBERSHIPS.NAME
        }
        val billable = DSL.`when`(EXPENSES.BILLABLE.isTrue, EXPENSES.AMOUNT_MINOR).otherwise(0L)
        val uninvoiced = DSL.`when`(EXPENSES.BILLABLE.isTrue.and(EXPENSES.INVOICE_ID.isNull), EXPENSES.AMOUNT_MINOR).otherwise(0L)
        val clientName: Field<String?> = if (groupBy == "project") CLIENTS.NAME else DSL.inline(null, SQLDataType.VARCHAR)
        val rows = dsl.select(key, name, clientName, EXPENSES.CURRENCY, DSL.sum(EXPENSES.AMOUNT_MINOR), DSL.sum(billable), DSL.sum(uninvoiced), DSL.count())
            .from(EXPENSES)
            .join(PROJECTS).on(PROJECTS.ID.eq(EXPENSES.PROJECT_ID))
            .join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .join(EXPENSE_CATEGORIES).on(EXPENSE_CATEGORIES.ID.eq(EXPENSES.CATEGORY_ID))
            .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(EXPENSES.MEMBERSHIP_ID))
            .where(expenses.visibility(m)).and(expenseCondition(f))
            .groupBy(listOfNotNull(key, name, CLIENTS.NAME.takeIf { groupBy == "project" }, EXPENSES.CURRENCY))
            .fetch()
        val grouped = rows.groupBy { it.value1() }.map { (id, parts) ->
            ExpenseReportRow(
                id = id, name = parts.first().value2(), clientName = parts.first().value3(),
                count = parts.sumOf { it.value8() },
                amounts = parts.map { ExpenseAmount(it.value4(), it.value5().toLong(), it.value6().toLong(), it.value7().toLong()) }.sortedBy { it.currency },
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val totals = grouped.flatMap { it.amounts }.groupBy { it.currency }.map { (currency, xs) ->
            ExpenseAmount(currency, xs.sumOf { it.amount }, xs.sumOf { it.billableAmount }, xs.sumOf { it.uninvoicedAmount })
        }.sortedBy { it.currency }
        return ExpenseReport(f.from, f.to, groupBy, grouped.sumOf { it.count }, totals, grouped)
    }

    // ---- shared ----------------------------------------------------------------

    /** Per-entry SQL for durations (a running timer counts up to now), rounding and amounts. */
    class EntryFields(val seconds: Field<Int>, val rounded: Field<Int>, val amount: Field<Long>, val cost: Field<Long>)

    // An overload rather than a default argument: Kotlin evaluates defaults on the Spring proxy,
    // whose fields are empty.
    fun fields(s: AccountSettings): EntryFields = fields(s, Instant.now(clock))

    fun fields(s: AccountSettings, now: Instant): EntryFields {
        val seconds = EntrySql.seconds(now)
        val rounded = EntrySql.rounded(seconds, s.roundingMinutes, s.roundingMode)
        return EntryFields(seconds, rounded, EntrySql.billableAmount(rounded), EntrySql.cost(seconds))
    }

    /** [f] as a condition on time entries (no join needed). */
    fun timeCondition(f: ReportFilter): Condition {
        var c = DSL.noCondition()
        f.from?.let { c = c.and(TIME_ENTRIES.SPENT_DATE.ge(it)) }
        f.to?.let { c = c.and(TIME_ENTRIES.SPENT_DATE.le(it)) }
        if (f.clientIds.isNotEmpty()) c = c.and(TIME_ENTRIES.PROJECT_ID.`in`(DSL.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.CLIENT_ID.`in`(f.clientIds))))
        if (f.projectIds.isNotEmpty()) c = c.and(TIME_ENTRIES.PROJECT_ID.`in`(f.projectIds))
        if (f.taskIds.isNotEmpty()) c = c.and(TIME_ENTRIES.TASK_ID.`in`(f.taskIds))
        if (f.membershipIds.isNotEmpty()) c = c.and(TIME_ENTRIES.MEMBERSHIP_ID.`in`(f.membershipIds))
        if (f.teamIds.isNotEmpty()) c = c.and(TIME_ENTRIES.MEMBERSHIP_ID.`in`(teamMembers(f.teamIds)))
        f.billable?.let { c = c.and(TIME_ENTRIES.BILLABLE.eq(it)) }
        if (f.approvalStates.isNotEmpty()) c = c.and(TIME_ENTRIES.APPROVAL_STATE.`in`(f.approvalStates))
        f.invoiced?.let { c = c.and(if (it) TIME_ENTRIES.INVOICE_ID.isNotNull else TIME_ENTRIES.INVOICE_ID.isNull) }
        return c
    }

    /** [f] as a condition on expenses (no join needed). Task filters don't apply. */
    fun expenseCondition(f: ReportFilter): Condition {
        var c = DSL.noCondition()
        f.from?.let { c = c.and(EXPENSES.SPENT_DATE.ge(it)) }
        f.to?.let { c = c.and(EXPENSES.SPENT_DATE.le(it)) }
        if (f.clientIds.isNotEmpty()) c = c.and(EXPENSES.PROJECT_ID.`in`(DSL.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.CLIENT_ID.`in`(f.clientIds))))
        if (f.projectIds.isNotEmpty()) c = c.and(EXPENSES.PROJECT_ID.`in`(f.projectIds))
        if (f.categoryIds.isNotEmpty()) c = c.and(EXPENSES.CATEGORY_ID.`in`(f.categoryIds))
        if (f.membershipIds.isNotEmpty()) c = c.and(EXPENSES.MEMBERSHIP_ID.`in`(f.membershipIds))
        if (f.teamIds.isNotEmpty()) c = c.and(EXPENSES.MEMBERSHIP_ID.`in`(teamMembers(f.teamIds)))
        f.billable?.let { c = c.and(EXPENSES.BILLABLE.eq(it)) }
        if (f.approvalStates.isNotEmpty()) c = c.and(EXPENSES.APPROVAL_STATE.`in`(f.approvalStates))
        f.invoiced?.let { c = c.and(if (it) EXPENSES.INVOICE_ID.isNotNull else EXPENSES.INVOICE_ID.isNull) }
        return c
    }

    private fun teamMembers(teamIds: List<UUID>) =
        DSL.select(TEAM_MEMBERSHIPS.MEMBERSHIP_ID).from(TEAM_MEMBERSHIPS).where(TEAM_MEMBERSHIPS.TEAM_ID.`in`(teamIds))

    private fun mergeAmounts(amounts: List<CurrencyAmount>) = amounts.groupBy { it.currency }
        .map { (currency, xs) -> CurrencyAmount(currency, xs.sumOf { it.billableAmount }, xs.sumOf { it.uninvoicedAmount }) }
        .sortedBy { it.currency }
}
