// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.approvals

import com.honestrobin.time.accounts.Access
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.audit.AuditService
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.TimesheetSubmissionsRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ForbiddenException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.time.Ref
import com.honestrobin.time.time.SubmissionView
import com.honestrobin.time.time.TimesheetService
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class ApprovalItem(
    val submission: SubmissionView,
    val person: Ref,
    val totalSeconds: Long,
    val billableSeconds: Long,
    val entryCount: Int,
    val expenseCount: Int,
    /** Entries in this week the caller can decide on (on projects they manage). */
    val decidableEntryCount: Int,
)

data class RejectRequest(val comment: String? = null)
data class ReopenRequest(val reason: String)

/**
 * Weekly approvals (spec §5.3): managers approve or reject submitted weeks for projects they
 * manage; approved entries lock. Admins can reopen an approved week, giving a reason that is audited.
 */
@Service
class ApprovalService(
    private val dsl: DSLContext,
    private val access: Access,
    private val accounts: AccountSettingsRepository,
    private val timesheets: TimesheetService,
    private val audit: AuditService,
    private val mailer: Mailer,
    private val props: HonestRobinProperties,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(m: Member, state: String?, weekStart: LocalDate?): List<ApprovalItem> {
        m.requireManagerOrAdmin()
        val visible = access.visibleMembershipIds(m)
        val subs = dsl.selectFrom(TIMESHEET_SUBMISSIONS)
            .where(if (visible != null) TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID.`in`(visible) else DSL.noCondition())
            .and(TIMESHEET_SUBMISSIONS.STATE.eq(state ?: "submitted"))
            .and(if (weekStart != null) TIMESHEET_SUBMISSIONS.WEEK_START_DATE.eq(weekStart) else DSL.noCondition())
            .orderBy(TIMESHEET_SUBMISSIONS.WEEK_START_DATE.desc(), TIMESHEET_SUBMISSIONS.SUBMITTED_AT)
            .limit(500)
            .fetch()
        val names = dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.`in`(subs.map { it.membershipId })).fetchMap(MEMBERSHIPS.ID, MEMBERSHIPS.NAME)
        val managed = access.managedProjectIds(m)
        return subs.map { s ->
            val week = weekOf(s)
            val stats = dsl.select(
                DSL.coalesce(DSL.sum(TIME_ENTRIES.DURATION_SECONDS).cast(SQLDataType.BIGINT), 0L),
                DSL.coalesce(DSL.sum(DSL.`when`(TIME_ENTRIES.BILLABLE.isTrue, TIME_ENTRIES.DURATION_SECONDS).otherwise(0)).cast(SQLDataType.BIGINT), 0L),
                DSL.count(),
                DSL.count().filterWhere(if (managed != null) TIME_ENTRIES.PROJECT_ID.`in`(managed) else DSL.trueCondition()),
            ).from(TIME_ENTRIES).where(week).fetchOne()!!
            val expenses = dsl.fetchCount(EXPENSES, expenseWeek(s))
            ApprovalItem(timesheets.submissionView(s), Ref(s.membershipId, names[s.membershipId] ?: "?"), stats.value1(), stats.value2(), stats.value3(), expenses, stats.value4())
        }
    }

    @Transactional
    fun approve(m: Member, id: UUID): ApprovalItem {
        m.requireWritable()
        val s = loadDecidable(m, id)
        if (s.state != "submitted") throw ConflictException("not_submitted", "Only submitted weeks can be approved")
        val scope = scope(m)
        dsl.update(TIME_ENTRIES)
            .set(TIME_ENTRIES.APPROVAL_STATE, "approved").set(TIME_ENTRIES.IS_LOCKED, true).set(TIME_ENTRIES.LOCKED_REASON, "approved")
            .where(weekOf(s)).and(TIME_ENTRIES.APPROVAL_STATE.eq("submitted")).and(scope.entries).execute()
        dsl.update(EXPENSES)
            .set(EXPENSES.APPROVAL_STATE, "approved").set(EXPENSES.IS_LOCKED, true).set(EXPENSES.LOCKED_REASON, "approved")
            .where(expenseWeek(s)).and(EXPENSES.APPROVAL_STATE.eq("submitted")).and(scope.expenses).execute()
        val pending = dsl.fetchExists(TIME_ENTRIES, weekOf(s).and(TIME_ENTRIES.APPROVAL_STATE.eq("submitted"))) ||
            dsl.fetchExists(EXPENSES, expenseWeek(s).and(EXPENSES.APPROVAL_STATE.eq("submitted")))
        if (!pending) decide(s, m, "approved", null)
        return item(m, s)
    }

    @Transactional
    fun reject(m: Member, id: UUID, comment: String?): ApprovalItem {
        m.requireWritable()
        val s = loadDecidable(m, id)
        if (s.state != "submitted") throw ConflictException("not_submitted", "Only submitted weeks can be rejected")
        val scope = scope(m)
        dsl.update(TIME_ENTRIES).set(TIME_ENTRIES.APPROVAL_STATE, "rejected")
            .where(weekOf(s)).and(TIME_ENTRIES.APPROVAL_STATE.eq("submitted")).and(scope.entries).execute()
        dsl.update(EXPENSES).set(EXPENSES.APPROVAL_STATE, "rejected")
            .where(expenseWeek(s)).and(EXPENSES.APPROVAL_STATE.eq("submitted")).and(scope.expenses).execute()
        decide(s, m, "rejected", comment?.trim()?.ifBlank { null })
        notifyRejected(m, s)
        return item(m, s)
    }

    /** Admin-only: unlocks an approved (or submitted) week so it can be edited again. */
    @Transactional
    fun reopen(m: Member, id: UUID, reason: String): ApprovalItem {
        m.requireWritable()
        m.requireAdmin()
        if (reason.isBlank()) throw ValidationException("reason", "Give a reason for reopening the week")
        val s = dsl.selectFrom(TIMESHEET_SUBMISSIONS).where(TIMESHEET_SUBMISSIONS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Submission")
        audit.withReason(reason.trim()) {
            dsl.update(TIME_ENTRIES)
                .set(TIME_ENTRIES.APPROVAL_STATE, "draft").set(TIME_ENTRIES.IS_LOCKED, false).set(TIME_ENTRIES.LOCKED_REASON, null as String?)
                .where(weekOf(s)).and(TIME_ENTRIES.INVOICE_ID.isNull).and(TIME_ENTRIES.APPROVAL_STATE.`in`("submitted", "approved", "rejected")).execute()
            dsl.update(EXPENSES)
                .set(EXPENSES.APPROVAL_STATE, "draft").set(EXPENSES.IS_LOCKED, false).set(EXPENSES.LOCKED_REASON, null as String?)
                .where(expenseWeek(s)).and(EXPENSES.INVOICE_ID.isNull).and(EXPENSES.APPROVAL_STATE.`in`("submitted", "approved", "rejected")).execute()
            decide(s, m, "reopened", reason.trim())
        }
        audit.record("timesheet.reopen", "timesheet_submissions", s.id, mapOf("week_start_date" to s.weekStartDate.toString(), "membership_id" to s.membershipId.toString()), reason.trim())
        return item(m, s)
    }

    private data class Scope(val entries: Condition, val expenses: Condition)

    private fun scope(m: Member): Scope {
        val managed = access.managedProjectIds(m) ?: return Scope(DSL.noCondition(), DSL.noCondition())
        return Scope(TIME_ENTRIES.PROJECT_ID.`in`(managed), EXPENSES.PROJECT_ID.`in`(managed))
    }

    private fun loadDecidable(m: Member, id: UUID): TimesheetSubmissionsRecord {
        m.requireManagerOrAdmin()
        val s = dsl.selectFrom(TIMESHEET_SUBMISSIONS).where(TIMESHEET_SUBMISSIONS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Submission")
        if (s.membershipId == m.membershipId && !m.isAdmin) throw ForbiddenException("You can't approve your own timesheet")
        val managed = access.managedProjectIds(m)
        if (managed != null && !dsl.fetchExists(TIME_ENTRIES, weekOf(s).and(TIME_ENTRIES.PROJECT_ID.`in`(managed)))) {
            throw ForbiddenException("This timesheet has no time on projects you manage")
        }
        return s
    }

    private fun decide(s: TimesheetSubmissionsRecord, m: Member, state: String, comment: String?) {
        s.state = state
        s.decidedBy = m.membershipId
        s.decidedAt = Instant.now(clock)
        s.comment = comment
        s.store()
    }

    private fun weekOf(s: TimesheetSubmissionsRecord): Condition =
        TIME_ENTRIES.MEMBERSHIP_ID.eq(s.membershipId).and(TIME_ENTRIES.SPENT_DATE.between(s.weekStartDate, s.weekStartDate.plusDays(6)))

    private fun expenseWeek(s: TimesheetSubmissionsRecord): Condition =
        EXPENSES.MEMBERSHIP_ID.eq(s.membershipId).and(EXPENSES.SPENT_DATE.between(s.weekStartDate, s.weekStartDate.plusDays(6)))

    private fun item(m: Member, s: TimesheetSubmissionsRecord) =
        list(m, s.state, s.weekStartDate).firstOrNull { it.submission.id == s.id }
            ?: ApprovalItem(timesheets.submissionView(s), Ref(s.membershipId, ""), 0, 0, 0, 0, 0)

    private fun notifyRejected(m: Member, s: TimesheetSubmissionsRecord) {
        val row = dsl.select(USERS.EMAIL, MEMBERSHIPS.NAME).from(MEMBERSHIPS).join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(MEMBERSHIPS.ID.eq(s.membershipId)).fetchOne() ?: return
        val settings = accounts.get(m.accountId)
        mailer.send(
            "timesheet-rejected", row.value1(), Locale.forLanguageTag(settings.locale),
            mapOf("name" to row.value2(), "manager" to m.name, "week" to s.weekStartDate.toString(), "comment" to s.comment, "link" to "${props.baseUrl}/week/${s.weekStartDate}"),
            subjectArgs = arrayOf(s.weekStartDate.toString()),
        )
    }
}

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "approvals", description = "Weekly timesheet approvals")
class ApprovalController(private val approvals: ApprovalService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) state: String?,
        @RequestParam(name = "week_start", required = false) weekStart: LocalDate?,
    ) = approvals.list(Current.member(), state, weekStart)

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: UUID) = approvals.approve(Current.member(), id)

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: UUID, @RequestBody(required = false) body: RejectRequest?) = approvals.reject(Current.member(), id, body?.comment)

    @PostMapping("/{id}/reopen")
    fun reopen(@PathVariable id: UUID, @RequestBody body: ReopenRequest) = approvals.reopen(Current.member(), id, body.reason)
}
