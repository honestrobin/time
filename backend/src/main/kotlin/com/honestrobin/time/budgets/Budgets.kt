// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.budgets

import com.honestrobin.time.accounts.Access
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.db.Tables.BUDGET_ALERTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.ProjectsRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.features.Feature
import com.honestrobin.time.platform.features.Features
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ForbiddenException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Views
import com.honestrobin.time.time.EntrySql
import com.fasterxml.jackson.annotation.JsonView
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class BudgetLine(
    val id: UUID,
    val name: String,
    val budgetSeconds: Long?,
    val spentSeconds: Long,
    @JsonView(Views.Rates::class) val budgetAmount: Long?,
    @JsonView(Views.Rates::class) val spentAmount: Long,
)

data class BudgetView(
    val projectId: UUID,
    val budgetBy: String,
    val isMonthly: Boolean,
    val periodStart: LocalDate?,
    /** "time" (seconds) or "money" (minor units). */
    val measure: String,
    val currency: String,
    val budgetSeconds: Long?,
    val spentSeconds: Long,
    val remainingSeconds: Long?,
    @JsonView(Views.Rates::class) val budgetAmount: Long?,
    @JsonView(Views.Rates::class) val spentAmount: Long,
    @JsonView(Views.Rates::class) val remainingAmount: Long?,
    val percentUsed: BigDecimal?,
    val isOverBudget: Boolean,
    val lines: List<BudgetLine>,
)

/** Computes budget usage from entries (spec §5.4), per Harvest's budget-by modes. */
@Service
class BudgetService(private val dsl: DSLContext, private val accounts: AccountSettingsRepository, private val access: Access, private val clock: Clock) {

    @Transactional(readOnly = true)
    fun forMember(m: Member, projectId: UUID): BudgetView {
        val p = dsl.selectFrom(PROJECTS).where(PROJECTS.ID.eq(projectId)).fetchOne() ?: throw NotFoundException("Project")
        val allowed = access.manages(m, projectId) || (p.showBudgetToAll && access.isAssigned(m.membershipId, projectId) && !isMoney(p.budgetBy))
        if (!allowed) throw ForbiddenException("You can't see this project's budget")
        return compute(p)
    }

    fun isMoney(budgetBy: String) = budgetBy in setOf("project_cost", "task_fees")

    fun compute(p: ProjectsRecord): BudgetView = computeMany(listOf(p), withLines = true).getValue(p.id)

    /**
     * Budget usage for many projects at once (the budget report), with a handful of grouped
     * queries instead of several per project. [withLines] adds spent per task or person.
     */
    fun computeMany(projects: List<ProjectsRecord>, withLines: Boolean = false): Map<UUID, BudgetView> {
        if (projects.isEmpty()) return emptyMap()
        val settings = accounts.get(projects.first().accountId)
        val currencies = dsl.select(CLIENTS.ID, CLIENTS.CURRENCY).from(CLIENTS).where(CLIENTS.ID.`in`(projects.map { it.clientId }.toSet()))
            .fetchMap(CLIENTS.ID, CLIENTS.CURRENCY)
        val monthStart = settings.today(clock).withDayOfMonth(1)
        val monthly = projects.filter { it.budgetIsMonthly }.map { it.id }.toSet()
        val inMonth = TIME_ENTRIES.SPENT_DATE.ge(monthStart).and(TIME_ENTRIES.SPENT_DATE.lt(monthStart.plusMonths(1)))
        val period: Condition = TIME_ENTRIES.PROJECT_ID.notIn(monthly).or(TIME_ENTRIES.PROJECT_ID.`in`(monthly).and(inMonth))
        // Harvest applies time rounding to budget calculations, per entry.
        val rounded = EntrySql.rounded(EntrySql.seconds(), settings.roundingMinutes, settings.roundingMode)
        val seconds = DSL.coalesce(DSL.sum(rounded).cast(SQLDataType.BIGINT), 0L)
        val money = DSL.coalesce(DSL.sum(EntrySql.billableAmount(rounded)).cast(SQLDataType.BIGINT), 0L)
        val ids = projects.map { it.id }

        fun spentBy(vararg keys: Field<UUID>): Map<List<UUID>, Pair<Long, Long>> =
            dsl.select(*keys, seconds, money).from(TIME_ENTRIES)
                .where(TIME_ENTRIES.ACCOUNT_ID.eq(settings.id)).and(TIME_ENTRIES.PROJECT_ID.`in`(ids)).and(period)
                .groupBy(*keys).fetch()
                .associate { r -> keys.indices.map { r.get(it) as UUID } to ((r.get(keys.size) as Long) to (r.get(keys.size + 1) as Long)) }

        val spent = spentBy(TIME_ENTRIES.PROJECT_ID)
        val withExpenses = projects.filter { it.budgetIncludeExpenses && it.budgetBy == "project_cost" }.map { it.id }
        val expenses: Map<UUID, Long> = if (withExpenses.isEmpty()) {
            emptyMap()
        } else {
            val expPeriod = EXPENSES.PROJECT_ID.notIn(monthly).or(EXPENSES.SPENT_DATE.ge(monthStart).and(EXPENSES.SPENT_DATE.lt(monthStart.plusMonths(1))))
            dsl.select(EXPENSES.PROJECT_ID, DSL.coalesce(DSL.sum(EXPENSES.AMOUNT_MINOR).cast(SQLDataType.BIGINT), 0L)).from(EXPENSES)
                .where(EXPENSES.PROJECT_ID.`in`(withExpenses)).and(expPeriod).groupBy(EXPENSES.PROJECT_ID)
                .fetch().associate { it.value1() to it.value2() }
        }

        val taskProjects = projects.filter { it.budgetBy in setOf("task", "task_fees") }.map { it.id }
        val personProjects = projects.filter { it.budgetBy == "person" }.map { it.id }
        val taskLines = if (taskProjects.isEmpty()) emptyList() else
            dsl.select(PROJECT_TASKS.PROJECT_ID, PROJECT_TASKS.TASK_ID, TASKS.NAME, PROJECT_TASKS.BUDGET_SECONDS, PROJECT_TASKS.BUDGET_AMOUNT)
                .from(PROJECT_TASKS).join(TASKS).on(TASKS.ID.eq(PROJECT_TASKS.TASK_ID)).where(PROJECT_TASKS.PROJECT_ID.`in`(taskProjects)).orderBy(TASKS.NAME).fetch()
        val personLines = if (personProjects.isEmpty()) emptyList() else
            dsl.select(PROJECT_MEMBERS.PROJECT_ID, PROJECT_MEMBERS.MEMBERSHIP_ID, MEMBERSHIPS.NAME, PROJECT_MEMBERS.BUDGET_SECONDS)
                .from(PROJECT_MEMBERS).join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(PROJECT_MEMBERS.MEMBERSHIP_ID)).where(PROJECT_MEMBERS.PROJECT_ID.`in`(personProjects))
                .orderBy(MEMBERSHIPS.NAME).fetch()
        val byTask = if (withLines && taskLines.isNotEmpty()) spentBy(TIME_ENTRIES.PROJECT_ID, TIME_ENTRIES.TASK_ID) else emptyMap()
        val byPerson = if (withLines && personLines.isNotEmpty()) spentBy(TIME_ENTRIES.PROJECT_ID, TIME_ENTRIES.MEMBERSHIP_ID) else emptyMap()

        return projects.associate { p ->
            val (spentSeconds, entryAmount) = spent[listOf(p.id)] ?: (0L to 0L)
            val spentAmount = entryAmount + (expenses[p.id] ?: 0L)
            val allLines: List<BudgetLine> = when (p.budgetBy) {
                "task", "task_fees" -> taskLines.filter { it.value1() == p.id }.map { r ->
                    val (s, a) = byTask[listOf(p.id, r.value2())] ?: (0L to 0L)
                    BudgetLine(r.value2(), r.value3(), r.value4(), s, r.value5(), a)
                }
                "person" -> personLines.filter { it.value1() == p.id }.map { r ->
                    val (s, a) = byPerson[listOf(p.id, r.value2())] ?: (0L to 0L)
                    BudgetLine(r.value2(), r.value3(), r.value4(), s, null, a)
                }
                else -> emptyList()
            }
            val budgetSeconds: Long? = when (p.budgetBy) {
                "project" -> p.budgetSeconds
                "task", "person" -> allLines.mapNotNull { it.budgetSeconds }.takeIf { it.isNotEmpty() }?.sum()
                else -> null
            }
            val budgetAmount: Long? = when (p.budgetBy) {
                "project_cost" -> p.budgetAmount
                "task_fees" -> allLines.mapNotNull { it.budgetAmount }.takeIf { it.isNotEmpty() }?.sum()
                else -> null
            }
            val isMoney = isMoney(p.budgetBy)
            val budget = if (isMoney) budgetAmount else budgetSeconds
            val used = if (isMoney) spentAmount else spentSeconds
            val percent = budget?.takeIf { it > 0 }?.let { BigDecimal(used * 100).divide(BigDecimal(it), 1, RoundingMode.HALF_UP) }
            p.id to BudgetView(
                projectId = p.id, budgetBy = p.budgetBy, isMonthly = p.budgetIsMonthly, periodStart = if (p.budgetIsMonthly) monthStart else null,
                measure = if (isMoney) "money" else "time", currency = currencies.getValue(p.clientId),
                budgetSeconds = budgetSeconds, spentSeconds = spentSeconds, remainingSeconds = budgetSeconds?.let { it - spentSeconds },
                budgetAmount = budgetAmount, spentAmount = spentAmount, remainingAmount = budgetAmount?.let { it - spentAmount },
                percentUsed = percent, isOverBudget = budget != null && used > budget, lines = if (withLines) allLines else emptyList(),
            )
        }
    }
}

/**
 * Budget alert emails to project managers at the alert percentage and at 100%,
 * once per crossing (spec §5.4). Dropping back below a threshold re-arms it. None while budgets
 * are switched off (platform.features): nobody could see the budget or turn the alert off.
 */
@Service
class BudgetAlertService(
    private val dsl: DSLContext,
    private val budgets: BudgetService,
    private val accounts: AccountSettingsRepository,
    private val mailer: Mailer,
    private val props: HonestRobinProperties,
    private val features: Features,
) {
    /** Called after any change to a project's time or expenses, inside the same transaction. */
    fun touch(projectId: UUID) {
        if (!features.isOn(Feature.BUDGETS)) return
        val p = dsl.selectFrom(PROJECTS).where(PROJECTS.ID.eq(projectId)).fetchOne() ?: return
        if (!p.notifyWhenOverBudget || p.budgetBy == "none") return
        val usage = budgets.compute(p)
        val budget = (if (budgets.isMoney(p.budgetBy)) usage.budgetAmount else usage.budgetSeconds)?.takeIf { it > 0 } ?: return
        val spent = if (budgets.isMoney(p.budgetBy)) usage.spentAmount else usage.spentSeconds
        val period = usage.periodStart ?: LocalDate.EPOCH
        val thresholds = listOfNotNull(p.budgetAlertPercent?.takeIf { it < BigDecimal(100) }, BigDecimal(100)).map { it.setScale(2) }
        for (t in thresholds) {
            val crossed = BigDecimal(spent).multiply(BigDecimal(100)) >= BigDecimal(budget).multiply(t)
            val existing = dsl.fetchExists(
                BUDGET_ALERTS,
                BUDGET_ALERTS.PROJECT_ID.eq(p.id).and(BUDGET_ALERTS.THRESHOLD.eq(t)).and(BUDGET_ALERTS.PERIOD_START.eq(period)),
            )
            if (crossed && !existing) {
                dsl.insertInto(BUDGET_ALERTS)
                    .set(BUDGET_ALERTS.ACCOUNT_ID, p.accountId).set(BUDGET_ALERTS.PROJECT_ID, p.id)
                    .set(BUDGET_ALERTS.THRESHOLD, t).set(BUDGET_ALERTS.PERIOD_START, period)
                    .onConflictDoNothing().execute()
                notify(p, t, usage)
            } else if (!crossed && existing) {
                dsl.deleteFrom(BUDGET_ALERTS)
                    .where(BUDGET_ALERTS.PROJECT_ID.eq(p.id)).and(BUDGET_ALERTS.THRESHOLD.eq(t)).and(BUDGET_ALERTS.PERIOD_START.eq(period)).execute()
            }
        }
    }

    private fun notify(p: ProjectsRecord, threshold: BigDecimal, usage: BudgetView) {
        val settings = accounts.get(p.accountId)
        val managers = dsl.select(USERS.EMAIL, MEMBERSHIPS.NAME).from(PROJECT_MEMBERS)
            .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(PROJECT_MEMBERS.MEMBERSHIP_ID))
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(PROJECT_MEMBERS.PROJECT_ID.eq(p.id)).and(PROJECT_MEMBERS.IS_MANAGER.isTrue)
            .and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
            .fetch()
        val recipients = managers.ifEmpty {
            dsl.select(USERS.EMAIL, MEMBERSHIPS.NAME).from(MEMBERSHIPS).join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
                .where(MEMBERSHIPS.ROLE.eq("admin")).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active")).fetch()
        }
        val percent = threshold.stripTrailingZeros().toPlainString()
        recipients.forEach { r ->
            mailer.send(
                "budget-alert", r.value1(), Locale.forLanguageTag(settings.locale),
                mapOf(
                    "name" to r.value2(), "project" to p.name, "percent" to percent, "used" to (usage.percentUsed?.toPlainString() ?: percent),
                    "over" to (threshold >= BigDecimal(100)), "link" to "${props.baseUrl}/projects/${p.id}",
                ),
                subjectArgs = arrayOf(p.name, percent),
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/projects/{id}/budget")
@Tag(name = "projects")
class BudgetController(private val budgets: BudgetService) {
    @GetMapping
    fun get(@PathVariable id: UUID) = budgets.forMember(Current.member(), id)
}
