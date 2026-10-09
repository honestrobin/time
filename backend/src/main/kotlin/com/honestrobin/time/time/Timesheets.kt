// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.accounts.Access
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIMESHEET_ROWS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.tables.records.TimesheetSubmissionsRecord
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ForbiddenException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Patch
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SubmissionView(
    val id: UUID,
    val weekStartDate: LocalDate,
    /** submitted, approved, rejected or reopened. */
    val state: String,
    val submittedAt: Instant,
    val decidedBy: Ref?,
    val decidedAt: Instant?,
    val comment: String?,
)

data class TimesheetRow(
    val project: ProjectRef,
    val client: Ref,
    val task: Ref,
    /** Seconds per day, Monday-first or as the account's week starts. */
    val days: List<Long>,
    /** Number of entries per day; a cell with more than one entry is edited in the day view. */
    val entryCounts: List<Int>,
    val total: Long,
    val isLocked: Boolean,
)

data class WeekView(
    val weekStart: LocalDate,
    val days: List<LocalDate>,
    val person: Ref,
    val capacitySeconds: Int,
    val rows: List<TimesheetRow>,
    val dayTotals: List<Long>,
    val total: Long,
    val billableTotal: Long,
    val approvalsEnabled: Boolean,
    val submission: SubmissionView?,
    /** Whether the caller may change this week. */
    val isReadOnly: Boolean,
    val entries: List<TimeEntryView>,
)

data class WeekCell(
    val membershipId: UUID? = null,
    val projectId: UUID,
    val taskId: UUID,
    val spentDate: LocalDate,
    val durationSeconds: Int,
)

data class WeekRow(val start: LocalDate, val membershipId: UUID? = null, val projectId: UUID, val taskId: UUID)
data class WeekRef(val start: LocalDate, val membershipId: UUID? = null)

@Service
class TimesheetService(
    private val dsl: DSLContext,
    private val access: Access,
    private val accounts: AccountSettingsRepository,
    private val entries: TimeEntryService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun week(m: Member, start: LocalDate?, membershipId: UUID?): WeekView {
        val settings = accounts.get(m.accountId)
        val owner = membershipId ?: m.membershipId
        requireCanView(m, owner)
        val weekStart = settings.weekStartOf(start ?: settings.today(clock))
        val days = (0L..6L).map { weekStart.plusDays(it) }
        val rows = dsl.selectFrom(TIME_ENTRIES)
            .where(TIME_ENTRIES.MEMBERSHIP_ID.eq(owner)).and(TIME_ENTRIES.SPENT_DATE.between(weekStart, weekStart.plusDays(6)))
            .and(if (owner == m.membershipId) org.jooq.impl.DSL.noCondition() else entries.visibility(m))
            .orderBy(TIME_ENTRIES.CREATED_AT).fetch()
        val views = entries.views(m.accountId, rows)
        val pinned = dsl.select(TIMESHEET_ROWS.PROJECT_ID, TIMESHEET_ROWS.TASK_ID).from(TIMESHEET_ROWS)
            .where(TIMESHEET_ROWS.MEMBERSHIP_ID.eq(owner)).and(TIMESHEET_ROWS.WEEK_START_DATE.eq(weekStart))
            .fetch().map { it.value1() to it.value2() }
        val combos = (views.map { it.project.id to it.task.id } + pinned).distinct()
        val names = comboNames(combos)
        val timesheetRows = combos.mapNotNull { (projectId, taskId) ->
            val n = names[projectId to taskId] ?: return@mapNotNull null
            val cell = views.filter { it.project.id == projectId && it.task.id == taskId }
            val perDay = days.map { d -> cell.filter { it.spentDate == d } }
            TimesheetRow(
                project = n.first, client = n.second, task = n.third,
                days = perDay.map { list -> list.sumOf { it.durationSeconds } }, entryCounts = perDay.map { it.size },
                total = cell.sumOf { it.durationSeconds }, isLocked = cell.any { it.isLocked || (settings.approvalsEnabled && it.approvalState == "submitted") },
            )
        }.sortedWith(compareBy({ it.client.name.lowercase() }, { it.project.name.lowercase() }, { it.task.name.lowercase() }))
        val person = dsl.select(MEMBERSHIPS.NAME, MEMBERSHIPS.WEEKLY_CAPACITY_SECONDS).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(owner)).fetchOne()
            ?: throw NotFoundException("Person")
        val submission = submission(owner, weekStart)
        val readOnly = settings.approvalsEnabled && when (submission?.state) {
            "approved" -> true
            "submitted" -> owner == m.membershipId && !m.isAdmin
            else -> false
        }
        return WeekView(
            weekStart = weekStart, days = days, person = Ref(owner, person.value1()), capacitySeconds = person.value2(),
            rows = timesheetRows, dayTotals = days.map { d -> views.filter { it.spentDate == d }.sumOf { it.durationSeconds } },
            total = views.sumOf { it.durationSeconds }, billableTotal = views.filter { it.billable }.sumOf { it.durationSeconds },
            approvalsEnabled = settings.approvalsEnabled, submission = submission?.let(::submissionView), isReadOnly = readOnly, entries = views,
        )
    }

    /** Sets the total for one cell (project × task × day). Cells with several entries are edited in the day view. */
    @Transactional
    fun setCell(m: Member, cell: WeekCell): WeekView {
        m.requireWritable()
        val owner = cell.membershipId ?: m.membershipId
        val existing = dsl.selectFrom(TIME_ENTRIES)
            .where(TIME_ENTRIES.MEMBERSHIP_ID.eq(owner)).and(TIME_ENTRIES.PROJECT_ID.eq(cell.projectId))
            .and(TIME_ENTRIES.TASK_ID.eq(cell.taskId)).and(TIME_ENTRIES.SPENT_DATE.eq(cell.spentDate))
            .fetch()
        when {
            existing.size > 1 -> throw ConflictException("multiple_entries", "This day has several entries for the task. Edit them in the day view.")
            existing.size == 1 -> {
                val e = existing.single()
                if (e.timerStartedAt != null) throw ConflictException("timer_running", "Stop the timer before editing this cell")
                if (cell.durationSeconds == 0 && e.notes.isNullOrBlank() && e.externalSource == null) {
                    entries.delete(m, e.id)
                } else {
                    entries.update(m, e.id, Patch(TimeEntryInput(durationSeconds = cell.durationSeconds), setOf("duration_seconds")))
                }
            }
            cell.durationSeconds > 0 -> entries.create(
                m,
                TimeEntryInput(membershipId = owner, projectId = cell.projectId, taskId = cell.taskId, spentDate = cell.spentDate, durationSeconds = cell.durationSeconds),
            )
        }
        return week(m, cell.spentDate, owner)
    }

    @Transactional
    fun addRow(m: Member, row: WeekRow): WeekView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val owner = row.membershipId ?: m.membershipId
        if (!access.canActOnTimeOf(m, owner, row.projectId)) throw ForbiddenException()
        entries.requireAssignable(owner, row.projectId, row.taskId)
        val weekStart = settings.weekStartOf(row.start)
        entries.requireWeekOpen(m, owner, weekStart, settings)
        dsl.insertInto(TIMESHEET_ROWS)
            .set(TIMESHEET_ROWS.ACCOUNT_ID, m.accountId).set(TIMESHEET_ROWS.MEMBERSHIP_ID, owner).set(TIMESHEET_ROWS.WEEK_START_DATE, weekStart)
            .set(TIMESHEET_ROWS.PROJECT_ID, row.projectId).set(TIMESHEET_ROWS.TASK_ID, row.taskId)
            .onConflictDoNothing().execute()
        return week(m, weekStart, owner)
    }

    /** Removes a row and deletes its entries for the week (all must be editable). */
    @Transactional
    fun removeRow(m: Member, row: WeekRow): WeekView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val owner = row.membershipId ?: m.membershipId
        if (!access.canActOnTimeOf(m, owner, row.projectId)) throw ForbiddenException()
        val weekStart = settings.weekStartOf(row.start)
        dsl.select(TIME_ENTRIES.ID).from(TIME_ENTRIES)
            .where(TIME_ENTRIES.MEMBERSHIP_ID.eq(owner)).and(TIME_ENTRIES.PROJECT_ID.eq(row.projectId)).and(TIME_ENTRIES.TASK_ID.eq(row.taskId))
            .and(TIME_ENTRIES.SPENT_DATE.between(weekStart, weekStart.plusDays(6)))
            .fetch(TIME_ENTRIES.ID).forEach { entries.delete(m, it) }
        dsl.deleteFrom(TIMESHEET_ROWS)
            .where(TIMESHEET_ROWS.MEMBERSHIP_ID.eq(owner)).and(TIMESHEET_ROWS.WEEK_START_DATE.eq(weekStart))
            .and(TIMESHEET_ROWS.PROJECT_ID.eq(row.projectId)).and(TIMESHEET_ROWS.TASK_ID.eq(row.taskId)).execute()
        return week(m, weekStart, owner)
    }

    /** Copies the rows (not the hours) of the most recent earlier week that has any. */
    @Transactional
    fun copyPreviousRows(m: Member, ref: WeekRef): WeekView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val owner = ref.membershipId ?: m.membershipId
        requireCanView(m, owner)
        val weekStart = settings.weekStartOf(ref.start)
        entries.requireWeekOpen(m, owner, weekStart, settings)
        val lastEntryDate = dsl.select(org.jooq.impl.DSL.max(TIME_ENTRIES.SPENT_DATE)).from(TIME_ENTRIES)
            .where(TIME_ENTRIES.MEMBERSHIP_ID.eq(owner)).and(TIME_ENTRIES.SPENT_DATE.lt(weekStart)).fetchOne()?.value1()
        val lastPinned = dsl.select(org.jooq.impl.DSL.max(TIMESHEET_ROWS.WEEK_START_DATE)).from(TIMESHEET_ROWS)
            .where(TIMESHEET_ROWS.MEMBERSHIP_ID.eq(owner)).and(TIMESHEET_ROWS.WEEK_START_DATE.lt(weekStart)).fetchOne()?.value1()
        val source = listOfNotNull(lastEntryDate?.let { settings.weekStartOf(it) }, lastPinned).maxOrNull() ?: return week(m, weekStart, owner)
        val combos = (
            dsl.selectDistinct(TIME_ENTRIES.PROJECT_ID, TIME_ENTRIES.TASK_ID).from(TIME_ENTRIES)
                .where(TIME_ENTRIES.MEMBERSHIP_ID.eq(owner)).and(TIME_ENTRIES.SPENT_DATE.between(source, source.plusDays(6)))
                .fetch().map { it.value1() to it.value2() } +
                dsl.select(TIMESHEET_ROWS.PROJECT_ID, TIMESHEET_ROWS.TASK_ID).from(TIMESHEET_ROWS)
                    .where(TIMESHEET_ROWS.MEMBERSHIP_ID.eq(owner)).and(TIMESHEET_ROWS.WEEK_START_DATE.eq(source))
                    .fetch().map { it.value1() to it.value2() }
            ).distinct()
        combos.forEach { (projectId, taskId) ->
            val assignable = runCatching { entries.requireAssignable(owner, projectId, taskId) }.isSuccess
            if (assignable && access.canActOnTimeOf(m, owner, projectId)) {
                dsl.insertInto(TIMESHEET_ROWS)
                    .set(TIMESHEET_ROWS.ACCOUNT_ID, m.accountId).set(TIMESHEET_ROWS.MEMBERSHIP_ID, owner).set(TIMESHEET_ROWS.WEEK_START_DATE, weekStart)
                    .set(TIMESHEET_ROWS.PROJECT_ID, projectId).set(TIMESHEET_ROWS.TASK_ID, taskId)
                    .onConflictDoNothing().execute()
            }
        }
        return week(m, weekStart, owner)
    }

    /** Submits the caller's week for approval; its time and expenses become read-only to them. */
    @Transactional
    fun submit(m: Member, ref: WeekRef): WeekView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        if (!settings.approvalsEnabled) throw ConflictException("approvals_disabled", "Timesheet approval is turned off for this account")
        val weekStart = settings.weekStartOf(ref.start)
        val end = weekStart.plusDays(6)
        val owner = m.membershipId
        val existing = submission(owner, weekStart)
        if (existing != null && existing.state in setOf("submitted", "approved")) throw ConflictException("already_submitted", "This week is already submitted")
        if (dsl.fetchExists(TIME_ENTRIES, TIME_ENTRIES.MEMBERSHIP_ID.eq(owner).and(TIME_ENTRIES.SPENT_DATE.between(weekStart, end)).and(TIME_ENTRIES.TIMER_STARTED_AT.isNotNull))) {
            throw ConflictException("timer_running", "Stop your running timer before submitting the week")
        }
        dsl.update(TIME_ENTRIES).set(TIME_ENTRIES.APPROVAL_STATE, "submitted")
            .where(TIME_ENTRIES.MEMBERSHIP_ID.eq(owner)).and(TIME_ENTRIES.SPENT_DATE.between(weekStart, end))
            .and(TIME_ENTRIES.APPROVAL_STATE.`in`("draft", "rejected")).execute()
        dsl.update(EXPENSES).set(EXPENSES.APPROVAL_STATE, "submitted")
            .where(EXPENSES.MEMBERSHIP_ID.eq(owner)).and(EXPENSES.SPENT_DATE.between(weekStart, end))
            .and(EXPENSES.APPROVAL_STATE.`in`("draft", "rejected")).execute()
        if (existing == null) {
            dsl.insertInto(TIMESHEET_SUBMISSIONS)
                .set(TIMESHEET_SUBMISSIONS.ACCOUNT_ID, m.accountId).set(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID, owner)
                .set(TIMESHEET_SUBMISSIONS.WEEK_START_DATE, weekStart).set(TIMESHEET_SUBMISSIONS.STATE, "submitted")
                .set(TIMESHEET_SUBMISSIONS.SUBMITTED_AT, Instant.now(clock)).execute()
        } else {
            existing.state = "submitted"
            existing.submittedAt = Instant.now(clock)
            existing.decidedBy = null
            existing.decidedAt = null
            existing.store()
        }
        return week(m, weekStart, owner)
    }

    fun submission(owner: UUID, weekStart: LocalDate): TimesheetSubmissionsRecord? =
        dsl.selectFrom(TIMESHEET_SUBMISSIONS).where(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID.eq(owner)).and(TIMESHEET_SUBMISSIONS.WEEK_START_DATE.eq(weekStart)).fetchOne()

    fun submissionView(r: TimesheetSubmissionsRecord): SubmissionView {
        val decider = r.decidedBy?.let { id -> dsl.select(MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(id)).fetchOne()?.value1()?.let { Ref(id, it) } }
        return SubmissionView(r.id, r.weekStartDate, r.state, r.submittedAt, decider, r.decidedAt, r.comment)
    }

    private fun requireCanView(m: Member, owner: UUID) {
        if (owner == m.membershipId) return
        val visible = access.visibleMembershipIds(m)
        if (visible != null && owner !in visible) throw ForbiddenException("You can't see this person's timesheet")
    }

    private fun comboNames(combos: List<Pair<UUID, UUID>>): Map<Pair<UUID, UUID>, Triple<ProjectRef, Ref, Ref>> {
        if (combos.isEmpty()) return emptyMap()
        val projects = dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.CODE, CLIENTS.ID, CLIENTS.NAME).from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .where(PROJECTS.ID.`in`(combos.map { it.first }.toSet())).fetchMap(PROJECTS.ID)
        val tasks = dsl.select(TASKS.ID, TASKS.NAME).from(TASKS).where(TASKS.ID.`in`(combos.map { it.second }.toSet())).fetchMap(TASKS.ID, TASKS.NAME)
        return combos.mapNotNull { (p, t) ->
            val pr = projects[p] ?: return@mapNotNull null
            (p to t) to Triple(ProjectRef(p, pr[PROJECTS.NAME], pr[PROJECTS.CODE]), Ref(pr[CLIENTS.ID], pr[CLIENTS.NAME]), Ref(t, tasks[t] ?: "?"))
        }.toMap()
    }
}

@RestController
@RequestMapping("/api/v1/timesheets/week")
@Tag(name = "timesheets", description = "Week timesheet: rows of project × task with a column per day")
class TimesheetController(private val timesheets: TimesheetService) {
    @GetMapping
    fun get(
        @RequestParam(required = false) start: LocalDate?,
        @RequestParam(name = "membership_id", required = false) membershipId: UUID?,
    ) = timesheets.week(Current.member(), start, membershipId)

    @PutMapping("/cell")
    fun setCell(@RequestBody body: WeekCell) = timesheets.setCell(Current.member(), body)

    @PostMapping("/rows")
    fun addRow(@RequestBody body: WeekRow) = timesheets.addRow(Current.member(), body)

    @DeleteMapping("/rows")
    fun removeRow(
        @RequestParam start: LocalDate,
        @RequestParam(name = "project_id") projectId: UUID,
        @RequestParam(name = "task_id") taskId: UUID,
        @RequestParam(name = "membership_id", required = false) membershipId: UUID?,
    ) = timesheets.removeRow(Current.member(), WeekRow(start, membershipId, projectId, taskId))

    @PostMapping("/copy_previous")
    fun copyPrevious(@RequestBody body: WeekRef) = timesheets.copyPreviousRows(Current.member(), body)

    @PostMapping("/submit")
    fun submit(@RequestBody body: WeekRef) = timesheets.submit(Current.member(), body)
}

