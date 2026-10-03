// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.platform.web.dateIdCursor
import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.accounts.Access
import com.honestrobin.time.accounts.AccountSettings
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.audit.AuditService
import com.honestrobin.time.budgets.BudgetAlertService
import com.honestrobin.time.catalog.RateSql
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.tables.records.TimeEntriesRecord
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ETags
import com.honestrobin.time.platform.web.ForbiddenException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Page
import com.honestrobin.time.platform.web.Patch
import com.honestrobin.time.platform.web.Patches
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.platform.web.Versioned
import com.honestrobin.time.platform.web.Views
import com.honestrobin.time.platform.web.clampLimit
import com.fasterxml.jackson.annotation.JsonView
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

const val MAX_ENTRY_SECONDS = 86_400
const val LONG_ENTRY_SECONDS = 12 * 3600

data class Ref(val id: UUID, val name: String)
data class ProjectRef(val id: UUID, val name: String, val code: String?)

/** Where an entry came from, e.g. a Jira issue captured by the browser extension. */
data class ExternalReference(
    val source: String,
    val id: String,
    val groupId: String? = null,
    val url: String? = null,
    val title: String? = null,
)

data class TimeEntryView(
    val id: UUID,
    val spentDate: LocalDate,
    val person: Ref,
    val client: Ref,
    val project: ProjectRef,
    val task: Ref,
    /** Tracked seconds, including the running part of an active timer. */
    val durationSeconds: Long,
    /** Duration after the account's rounding; used on invoices and in reports. */
    val roundedSeconds: Long,
    val isRunning: Boolean,
    val timerStartedAt: Instant?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val notes: String?,
    val billable: Boolean,
    val currency: String,
    @JsonView(Views.Rates::class) val billableRate: Long,
    @JsonView(Views.Rates::class) val costRate: Long,
    @JsonView(Views.Rates::class) val billableAmount: Long,
    @JsonView(Views.Rates::class) val costAmount: Long,
    /** draft, submitted, approved or rejected. */
    val approvalState: String,
    val isLocked: Boolean,
    val lockedReason: String?,
    val invoiceId: UUID?,
    val externalReference: ExternalReference?,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class TimeEntryInput(
    /** Whose time this is; defaults to the caller. Managers may track for people on their projects. */
    val membershipId: UUID? = null,
    val projectId: UUID? = null,
    val taskId: UUID? = null,
    val spentDate: LocalDate? = null,
    /** Omit duration and times to start a timer (like Harvest). */
    val durationSeconds: Int? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val notes: String? = null,
    val billable: Boolean? = null,
    val externalReference: ExternalReference? = null,
)

data class TimeEntryFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val membershipId: UUID? = null,
    val projectId: UUID? = null,
    val clientId: UUID? = null,
    val taskId: UUID? = null,
    val billable: Boolean? = null,
    val isRunning: Boolean? = null,
    val approvalState: String? = null,
    val invoiced: Boolean? = null,
)

data class UnlockRequest(val reason: String)

const val MAX_BULK = 1000

data class BulkTimeEntryUpdate(
    val ids: List<UUID> = emptyList(),
    /** Moving entries to another project needs a task on that project too. */
    val projectId: UUID? = null,
    val taskId: UUID? = null,
    val billable: Boolean? = null,
)

data class BulkSkip(val id: UUID, val code: String, val message: String)

data class BulkUpdateResult(val updated: Int, val skipped: List<BulkSkip>)

@Service
class TimeEntryService(
    private val dsl: DSLContext,
    private val access: Access,
    private val accounts: AccountSettingsRepository,
    private val budgets: BudgetAlertService,
    private val audit: AuditService,
    private val funnel: Funnel,
    private val clock: Clock,
) {
    // ---- queries -----------------------------------------------------------

    @Transactional(readOnly = true)
    fun list(m: Member, f: TimeEntryFilter, cursor: String?, limit: Int?): Page<TimeEntryView> {
        val n = clampLimit(limit, 200)
        val seek = cursor?.let { c ->
            val (d, id) = dateIdCursor(c)
            DSL.row(TIME_ENTRIES.SPENT_DATE, TIME_ENTRIES.ID).lt(d, id)
        } ?: DSL.noCondition()
        val rows = dsl.selectFrom(TIME_ENTRIES)
            .where(visibility(m)).and(filter(f)).and(seek)
            .orderBy(TIME_ENTRIES.SPENT_DATE.desc(), TIME_ENTRIES.ID.desc())
            .limit(n + 1).fetch()
        return Page.of(views(m.accountId, rows), n) { "${it.spentDate}_${it.id}" }
    }

    @Transactional(readOnly = true)
    fun get(m: Member, id: UUID): TimeEntryView = views(m.accountId, listOf(loadVisible(m, id))).single()

    @Transactional(readOnly = true)
    fun running(m: Member): TimeEntryView? =
        dsl.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.MEMBERSHIP_ID.eq(m.membershipId)).and(TIME_ENTRIES.TIMER_STARTED_AT.isNotNull).fetchOne()
            ?.let { views(m.accountId, listOf(it)).single() }

    fun visibility(m: Member): Condition {
        val managed = access.managedProjectIds(m) ?: return DSL.noCondition()
        return TIME_ENTRIES.MEMBERSHIP_ID.eq(m.membershipId).or(TIME_ENTRIES.PROJECT_ID.`in`(managed))
    }

    fun filter(f: TimeEntryFilter): Condition {
        var c = DSL.noCondition()
        f.from?.let { c = c.and(TIME_ENTRIES.SPENT_DATE.ge(it)) }
        f.to?.let { c = c.and(TIME_ENTRIES.SPENT_DATE.le(it)) }
        f.membershipId?.let { c = c.and(TIME_ENTRIES.MEMBERSHIP_ID.eq(it)) }
        f.projectId?.let { c = c.and(TIME_ENTRIES.PROJECT_ID.eq(it)) }
        f.taskId?.let { c = c.and(TIME_ENTRIES.TASK_ID.eq(it)) }
        f.clientId?.let { c = c.and(TIME_ENTRIES.PROJECT_ID.`in`(DSL.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.CLIENT_ID.eq(it)))) }
        f.billable?.let { c = c.and(TIME_ENTRIES.BILLABLE.eq(it)) }
        f.isRunning?.let { c = c.and(if (it) TIME_ENTRIES.TIMER_STARTED_AT.isNotNull else TIME_ENTRIES.TIMER_STARTED_AT.isNull) }
        f.approvalState?.let { c = c.and(TIME_ENTRIES.APPROVAL_STATE.eq(it)) }
        f.invoiced?.let { c = c.and(if (it) TIME_ENTRIES.INVOICE_ID.isNotNull else TIME_ENTRIES.INVOICE_ID.isNull) }
        return c
    }

    // ---- commands ----------------------------------------------------------

    @Transactional
    fun create(m: Member, input: TimeEntryInput): TimeEntryView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val owner = input.membershipId ?: m.membershipId
        val projectId = input.projectId ?: throw ValidationException("project_id", "Choose a project")
        val taskId = input.taskId ?: throw ValidationException("task_id", "Choose a task")
        if (!access.canActOnTimeOf(m, owner, projectId)) throw ForbiddenException("You can only track time for people on projects you manage")
        val today = settings.today(clock)
        val date = input.spentDate ?: today
        val assignment = requireAssignable(owner, projectId, taskId)
        requireWeekOpen(m, owner, date, settings)

        val startTimer = input.durationSeconds == null && input.endTime == null
        val duration = when {
            input.durationSeconds != null -> input.durationSeconds
            input.startTime != null && input.endTime != null -> between(input.startTime, input.endTime)
            input.endTime != null -> throw ValidationException("start_time", "Enter a start time")
            else -> 0
        }
        validateDuration(duration)
        if (startTimer) {
            if (owner != m.membershipId) throw ValidationException("membership_id", "Timers can only be started for yourself")
            if (date != today) throw ValidationException("spent_date", "Timers can only run today. Enter a duration for other days.")
            lockOwner(owner)
            stopRunning(owner, settings)
        }
        val billable = assignment.billable && (input.billable ?: true)
        val (rate, cost) = RateSql.resolve(dsl, projectId, taskId, owner, billable)
        val r = dsl.newRecord(TIME_ENTRIES).apply {
            accountId = m.accountId
            membershipId = owner
            this.projectId = projectId
            this.taskId = taskId
            spentDate = date
            durationSeconds = duration
            startTime = input.startTime ?: if (startTimer) LocalTime.now(clock.withZone(settings.timezone)).withNano(0) else null
            endTime = input.endTime
            timerStartedAt = if (startTimer) Instant.now(clock) else null
            notes = input.notes?.trim()?.ifBlank { null }
            this.billable = billable
            billableRateSnapshot = rate
            costRateSnapshot = cost
        }
        applyExternal(r, input.externalReference)
        r.store()
        budgets.touch(projectId)
        if (startTimer) funnel.milestone(m.accountId, Funnel.FIRST_TIMER_STARTED)
        return views(m.accountId, listOf(r)).single()
    }

    @Transactional
    fun update(m: Member, id: UUID, patch: Patch<TimeEntryInput>): TimeEntryView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val r = loadVisible(m, id)
        ETags.checkIfMatch(r.updatedAt)
        applyUpdate(m, r, patch, settings).forEach(budgets::touch)
        return views(m.accountId, listOf(r)).single()
    }

    /**
     * Changes project, task or billable on many entries at once (spec §12, detailed report).
     * Entries that can't change (invoiced, approved, locked, in a closed week) are skipped and
     * reported, the rest are saved.
     */
    @Transactional
    fun bulkUpdate(m: Member, input: BulkTimeEntryUpdate): BulkUpdateResult {
        m.requireWritable()
        val ids = input.ids.distinct()
        if (ids.isEmpty()) throw ValidationException("ids", "Choose at least one entry")
        if (ids.size > MAX_BULK) throw ValidationException("ids", "Change at most $MAX_BULK entries at a time")
        if (input.projectId == null && input.taskId == null && input.billable == null) throw ValidationException("project_id", "Choose what to change")
        if (input.projectId != null && input.taskId == null) throw ValidationException("task_id", "Choose a task on the new project")
        val settings = accounts.get(m.accountId)
        val rows = dsl.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.ID.`in`(ids)).and(visibility(m))
            .orderBy(TIME_ENTRIES.ID).forUpdate().fetch()
        val found = rows.map { it.id }.toSet()
        val skipped = ids.filter { it !in found }.map { BulkSkip(it, "not_found", "Time entry not found") }.toMutableList()
        val fields = buildSet {
            if (input.projectId != null) add("project_id")
            if (input.taskId != null) add("task_id")
            if (input.billable != null) add("billable")
        }
        val patch = Patch(TimeEntryInput(projectId = input.projectId, taskId = input.taskId, billable = input.billable), fields)
        val touched = mutableSetOf<UUID>()
        var updated = 0
        for (r in rows) {
            try {
                touched += applyUpdate(m, r, patch, settings)
                updated++
            } catch (e: ApiException) {
                skipped += BulkSkip(r.id, e.code, e.fields.values.firstOrNull() ?: e.message)
            }
        }
        touched.forEach(budgets::touch)
        return BulkUpdateResult(updated, skipped)
    }

    /** Applies [patch] to [r] and saves it; returns the projects whose budgets may have moved. */
    private fun applyUpdate(m: Member, r: TimeEntriesRecord, patch: Patch<TimeEntryInput>, settings: AccountSettings): Set<UUID> {
        requireEditable(m, r, settings)
        val i = patch.value
        if (i.membershipId != null && i.membershipId != r.membershipId) throw ValidationException("membership_id", "Entries can't be moved to another person")
        val oldProject = r.projectId

        var reRate = false
        if ((i.projectId != null && i.projectId != r.projectId) || (i.taskId != null && i.taskId != r.taskId)) {
            val projectId = i.projectId ?: r.projectId
            val taskId = i.taskId ?: r.taskId
            if (!access.canActOnTimeOf(m, r.membershipId, projectId)) throw ForbiddenException("You don't manage that project")
            val a = requireAssignable(r.membershipId, projectId, taskId)
            r.projectId = projectId
            r.taskId = taskId
            if (i.billable == null) r.billable = a.billable
            reRate = true
        }
        i.spentDate?.let {
            if (it != r.spentDate) {
                if (r.timerStartedAt != null) throw ValidationException("spent_date", "Stop the timer before moving the entry to another day")
                requireWeekOpen(m, r.membershipId, it, settings)
                r.spentDate = it
            }
        }
        i.billable?.let {
            val a = requireAssignable(r.membershipId, r.projectId, r.taskId, requireActive = false)
            r.billable = a.billable && it
            reRate = true
        }
        patch.field("notes", { notes }) { r.notes = it?.trim()?.ifBlank { null } }
        patch.field("start_time", { startTime }) { r.startTime = it }
        patch.field("end_time", { endTime }) { r.endTime = it }
        if (i.durationSeconds != null) {
            validateDuration(i.durationSeconds)
            r.durationSeconds = i.durationSeconds
            if (r.timerStartedAt != null) r.timerStartedAt = Instant.now(clock)
        } else if ((patch.has("start_time") || patch.has("end_time")) && r.startTime != null && r.endTime != null && r.timerStartedAt == null) {
            r.durationSeconds = between(r.startTime, r.endTime).also(::validateDuration)
        }
        patch.field("external_reference", { externalReference }) { applyExternal(r, it) }
        if (reRate) {
            val (rate, cost) = RateSql.resolve(dsl, r.projectId, r.taskId, r.membershipId, r.billable)
            r.billableRateSnapshot = rate
            r.costRateSnapshot = cost
        }
        r.store()
        return setOf(r.projectId, oldProject)
    }

    @Transactional
    fun delete(m: Member, id: UUID) {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val r = loadVisible(m, id)
        ETags.checkIfMatch(r.updatedAt)
        requireEditable(m, r, settings)
        r.delete()
        budgets.touch(r.projectId)
    }

    /** Starts the timer on an entry. An entry from an earlier day is continued as a new entry today. */
    @Transactional
    fun start(m: Member, id: UUID): TimeEntryView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val r = loadVisible(m, id)
        if (r.membershipId != m.membershipId) throw ForbiddenException("You can only start your own timers")
        if (r.timerStartedAt != null) return views(m.accountId, listOf(r)).single()
        val today = settings.today(clock)
        if (r.spentDate != today || r.isLocked || r.approvalState in setOf("submitted", "approved")) {
            return create(
                m,
                TimeEntryInput(
                    projectId = r.projectId, taskId = r.taskId, notes = r.notes, billable = r.billable,
                    externalReference = r.externalSource?.let { ExternalReference(it, r.externalId, r.externalGroupId, r.externalUrl, r.externalTitle) },
                ),
            )
        }
        requireEditable(m, r, settings)
        lockOwner(m.membershipId)
        stopRunning(m.membershipId, settings)
        r.refresh()
        r.timerStartedAt = Instant.now(clock)
        r.endTime = null
        r.store()
        funnel.milestone(m.accountId, Funnel.FIRST_TIMER_STARTED)
        return views(m.accountId, listOf(r)).single()
    }

    @Transactional
    fun stop(m: Member, id: UUID): TimeEntryView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val r = loadVisible(m, id)
        if (r.membershipId != m.membershipId && !access.manages(m, r.projectId)) throw ForbiddenException()
        if (r.timerStartedAt != null) finishTimer(r, settings)
        return views(m.accountId, listOf(r)).single()
    }

    /** Admin-only: unlocks an approved entry, with a reason recorded in the audit log. */
    @Transactional
    fun unlock(m: Member, id: UUID, reason: String): TimeEntryView {
        m.requireAdmin()
        if (reason.isBlank()) throw ValidationException("reason", "Give a reason for unlocking")
        val r = loadVisible(m, id)
        if (r.invoiceId != null) throw ConflictException("invoiced", "This entry is on an invoice. Remove it from the invoice to unlock it.")
        audit.withReason(reason.trim()) {
            r.isLocked = false
            r.lockedReason = null
            r.approvalState = "draft"
            r.store()
        }
        return views(m.accountId, listOf(r)).single()
    }

    // ---- rules -------------------------------------------------------------

    data class Assignment(val billable: Boolean)

    /** The person must be on the project and the task on the project, both active. */
    fun requireAssignable(membershipId: UUID, projectId: UUID, taskId: UUID, requireActive: Boolean = true): Assignment {
        val row = dsl.select(PROJECTS.IS_ACTIVE, PROJECTS.IS_BILLABLE, PROJECT_TASKS.BILLABLE, PROJECT_TASKS.IS_ACTIVE, PROJECT_MEMBERS.IS_ACTIVE)
            .from(PROJECTS)
            .leftJoin(PROJECT_TASKS).on(PROJECT_TASKS.PROJECT_ID.eq(PROJECTS.ID).and(PROJECT_TASKS.TASK_ID.eq(taskId)))
            .leftJoin(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(membershipId)))
            .where(PROJECTS.ID.eq(projectId))
            .fetchOne() ?: throw ValidationException("project_id", "Unknown project")
        if (requireActive) {
            if (row[PROJECTS.IS_ACTIVE] != true) throw ValidationException("project_id", "This project is archived")
            if (row[PROJECT_MEMBERS.IS_ACTIVE] != true) throw ValidationException("project_id", "This person is not assigned to the project")
            if (row[PROJECT_TASKS.IS_ACTIVE] != true) throw ValidationException("task_id", "This task is not on the project")
        }
        return Assignment(billable = row[PROJECTS.IS_BILLABLE] == true && row[PROJECT_TASKS.BILLABLE] == true)
    }

    /** Submitted and approved weeks are read-only to their owner (spec §5.3). */
    fun requireWeekOpen(m: Member, owner: UUID, date: LocalDate, settings: AccountSettings) {
        if (!settings.approvalsEnabled) return
        val state = dsl.select(TIMESHEET_SUBMISSIONS.STATE).from(TIMESHEET_SUBMISSIONS)
            .where(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID.eq(owner)).and(TIMESHEET_SUBMISSIONS.WEEK_START_DATE.eq(settings.weekStartOf(date)))
            .fetchOne()?.value1()
        if (state == "approved") throw ConflictException("week_approved", "This week has been approved and is locked")
        if (state == "submitted" && owner == m.membershipId && !m.isAdmin) {
            throw ConflictException("week_submitted", "This week has been submitted for approval and can't be changed")
        }
    }

    private fun requireEditable(m: Member, r: TimeEntriesRecord, settings: AccountSettings) {
        if (r.invoiceId != null) throw ConflictException("invoiced", "This entry has been invoiced and is locked")
        if (r.isLocked) throw ConflictException("entry_locked", "This entry is locked" + (r.lockedReason?.let { " ($it)" } ?: ""))
        if (r.approvalState == "approved") throw ConflictException("week_approved", "This entry has been approved and is locked")
        if (r.approvalState == "submitted" && r.membershipId == m.membershipId && !m.isAdmin) {
            throw ConflictException("week_submitted", "This entry has been submitted for approval and can't be changed")
        }
        requireWeekOpen(m, r.membershipId, r.spentDate, settings)
    }

    private fun validateDuration(seconds: Int) {
        if (seconds < 0) throw ValidationException("duration_seconds", "Time can't be negative")
        if (seconds > MAX_ENTRY_SECONDS) throw ValidationException("duration_seconds", "An entry can't be longer than 24 hours")
    }

    private fun between(start: LocalTime, end: LocalTime): Int {
        if (end.isBefore(start)) throw ValidationException("end_time", "The end time is before the start time")
        return Duration.between(start, end).seconds.toInt()
    }

    private fun applyExternal(r: TimeEntriesRecord, ref: ExternalReference?) {
        r.externalSource = ref?.source?.take(50)
        r.externalId = ref?.id?.take(200)
        r.externalGroupId = ref?.groupId?.take(200)
        r.externalUrl = ref?.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(2000)
        r.externalTitle = ref?.title?.take(500)
    }

    /** Serialises timer changes per person so two starts can't race past the one-running-timer rule. */
    private fun lockOwner(membershipId: UUID) {
        dsl.select(MEMBERSHIPS.ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(membershipId)).forUpdate().fetch()
    }

    /** Stops the person's running timer, if any (starting a timer stops the previous one, spec §5.2). */
    fun stopRunning(membershipId: UUID, settings: AccountSettings) {
        dsl.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.MEMBERSHIP_ID.eq(membershipId)).and(TIME_ENTRIES.TIMER_STARTED_AT.isNotNull)
            .fetch().forEach { finishTimer(it, settings) }
    }

    private fun finishTimer(r: TimeEntriesRecord, settings: AccountSettings) {
        val now = Instant.now(clock)
        val elapsed = Duration.between(r.timerStartedAt, now).seconds.coerceAtLeast(0)
        r.durationSeconds = (r.durationSeconds + elapsed).coerceAtMost(MAX_ENTRY_SECONDS.toLong()).toInt()
        r.timerStartedAt = null
        // A timer that crossed midnight keeps its spent_date (Harvest behaviour); the end time is informational.
        if (r.startTime != null) r.endTime = LocalTime.now(clock.withZone(settings.timezone)).withNano(0)
        r.store()
        budgets.touch(r.projectId)
    }

    private fun loadVisible(m: Member, id: UUID): TimeEntriesRecord =
        dsl.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.ID.eq(id)).and(visibility(m)).fetchOne() ?: throw NotFoundException("Time entry")

    fun views(accountId: UUID, rows: List<TimeEntriesRecord>): List<TimeEntryView> {
        if (rows.isEmpty()) return emptyList()
        val settings = accounts.get(accountId)
        val people = dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.`in`(rows.map { it.membershipId }.toSet()))
            .fetchMap(MEMBERSHIPS.ID, MEMBERSHIPS.NAME)
        val projects = dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.CODE, CLIENTS.ID, CLIENTS.NAME, CLIENTS.CURRENCY)
            .from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .where(PROJECTS.ID.`in`(rows.map { it.projectId }.toSet())).fetchMap(PROJECTS.ID)
        val tasks = dsl.select(TASKS.ID, TASKS.NAME).from(TASKS).where(TASKS.ID.`in`(rows.map { it.taskId }.toSet())).fetchMap(TASKS.ID, TASKS.NAME)
        val now = Instant.now(clock)
        return rows.map { r ->
            val p = projects.getValue(r.projectId)
            val running = r.timerStartedAt != null
            val duration = r.durationSeconds.toLong() +
                (if (running) Duration.between(r.timerStartedAt, now).seconds.coerceAtLeast(0) else 0)
            val rounded = Money.roundSeconds(duration.coerceAtMost(MAX_ENTRY_SECONDS.toLong()), settings.roundingMinutes, settings.roundingMode)
            TimeEntryView(
                id = r.id, spentDate = r.spentDate, person = Ref(r.membershipId, people[r.membershipId] ?: "?"),
                client = Ref(p[CLIENTS.ID], p[CLIENTS.NAME]), project = ProjectRef(r.projectId, p[PROJECTS.NAME], p[PROJECTS.CODE]),
                task = Ref(r.taskId, tasks[r.taskId] ?: "?"), durationSeconds = duration, roundedSeconds = rounded, isRunning = running,
                timerStartedAt = r.timerStartedAt, startTime = r.startTime, endTime = r.endTime, notes = r.notes, billable = r.billable,
                currency = p[CLIENTS.CURRENCY], billableRate = r.billableRateSnapshot, costRate = r.costRateSnapshot,
                billableAmount = if (r.billable) Money.forDuration(rounded, r.billableRateSnapshot) else 0,
                costAmount = Money.forDuration(duration, r.costRateSnapshot),
                approvalState = r.approvalState, isLocked = r.isLocked || r.invoiceId != null || r.approvalState == "approved",
                lockedReason = r.lockedReason ?: if (r.invoiceId != null) "invoiced" else null, invoiceId = r.invoiceId,
                externalReference = r.externalSource?.let { ExternalReference(it, r.externalId ?: "", r.externalGroupId, r.externalUrl, r.externalTitle) },
                createdAt = r.createdAt, updatedAt = r.updatedAt,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/time_entries")
@Tag(name = "time_entries", description = "Time entries and timers")
class TimeEntryController(private val entries: TimeEntryService, private val patches: Patches) {
    @GetMapping
    fun list(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "membership_id", required = false) membershipId: UUID?,
        @RequestParam(name = "project_id", required = false) projectId: UUID?,
        @RequestParam(name = "client_id", required = false) clientId: UUID?,
        @RequestParam(name = "task_id", required = false) taskId: UUID?,
        @RequestParam(required = false) billable: Boolean?,
        @RequestParam(name = "is_running", required = false) isRunning: Boolean?,
        @RequestParam(name = "approval_state", required = false) approvalState: String?,
        @RequestParam(required = false) invoiced: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = entries.list(
        Current.member(),
        TimeEntryFilter(from, to, membershipId, projectId, clientId, taskId, billable, isRunning, approvalState, invoiced),
        cursor, limit,
    )

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = entries.get(Current.member(), id)

    /** Creates an entry. Without a duration or end time, the entry starts as a running timer. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: TimeEntryInput) = entries.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(schema = Schema(implementation = TimeEntryInput::class))]) @RequestBody body: JsonNode,
    ) = entries.update(Current.member(), id, patches.parse(body))

    /** Changes project, task or billable on up to 1,000 entries; entries that can't change are skipped. */
    @PostMapping("/bulk")
    fun bulk(@RequestBody body: BulkTimeEntryUpdate) = entries.bulkUpdate(Current.member(), body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = entries.delete(Current.member(), id)

    @PostMapping("/{id}/start")
    fun start(@PathVariable id: UUID) = entries.start(Current.member(), id)

    @PostMapping("/{id}/stop")
    fun stop(@PathVariable id: UUID) = entries.stop(Current.member(), id)

    @PostMapping("/{id}/unlock")
    fun unlock(@PathVariable id: UUID, @RequestBody body: UnlockRequest) = entries.unlock(Current.member(), id, body.reason)
}

@RestController
@RequestMapping("/api/v1/me/timer")
@Tag(name = "me")
class MyTimerController(private val entries: TimeEntryService) {
    /** The caller's running timer, or 204 when none is running. */
    @GetMapping
    fun running(): ResponseEntity<TimeEntryView> = entries.running(Current.member())?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
}

