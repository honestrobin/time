// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.platform.web.idCursor
import com.honestrobin.time.accounts.Access
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.tables.records.ProjectMembersRecord
import com.honestrobin.time.db.tables.records.ProjectTasksRecord
import com.honestrobin.time.db.tables.records.ProjectsRecord
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.Role
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
import tools.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocBody

data class ClientRef(val id: UUID, val name: String, val currency: String)

data class ProjectTaskView(
    val id: UUID,
    val taskId: UUID,
    val name: String,
    val billable: Boolean,
    @JsonView(Views.Rates::class) val hourlyRate: Long?,
    val budgetSeconds: Long?,
    @JsonView(Views.Rates::class) val budgetAmount: Long?,
    val isActive: Boolean,
)

data class ProjectMemberView(
    val id: UUID,
    val membershipId: UUID,
    val name: String,
    val isManager: Boolean,
    val useDefaultRates: Boolean,
    @JsonView(Views.Rates::class) val hourlyRate: Long?,
    val budgetSeconds: Long?,
    val isActive: Boolean,
)

data class ProjectView(
    val id: UUID,
    val client: ClientRef,
    val name: String,
    val code: String?,
    val isActive: Boolean,
    val isBillable: Boolean,
    val isFixedFee: Boolean,
    /** project, tasks, people or none (Harvest's bill-by modes). */
    val billBy: String,
    @JsonView(Views.Rates::class) val hourlyRate: Long?,
    @JsonView(Views.Rates::class) val feeAmount: Long?,
    /** project, project_cost, task, task_fees, person or none. */
    val budgetBy: String,
    val budgetIsMonthly: Boolean,
    val budgetSeconds: Long?,
    @JsonView(Views.Rates::class) val budgetAmount: Long?,
    val budgetIncludeExpenses: Boolean,
    val budgetAlertPercent: BigDecimal?,
    val notifyWhenOverBudget: Boolean,
    val showBudgetToAll: Boolean,
    val startsOn: LocalDate?,
    val endsOn: LocalDate?,
    val notes: String?,
    val tasks: List<ProjectTaskView>,
    val members: List<ProjectMemberView>,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class ProjectInput(
    val clientId: UUID? = null,
    val name: String? = null,
    val code: String? = null,
    val isActive: Boolean? = null,
    val isBillable: Boolean? = null,
    val isFixedFee: Boolean? = null,
    val billBy: String? = null,
    val hourlyRate: Long? = null,
    val feeAmount: Long? = null,
    val budgetBy: String? = null,
    val budgetIsMonthly: Boolean? = null,
    val budgetSeconds: Long? = null,
    val budgetAmount: Long? = null,
    val budgetIncludeExpenses: Boolean? = null,
    val budgetAlertPercent: BigDecimal? = null,
    val notifyWhenOverBudget: Boolean? = null,
    val showBudgetToAll: Boolean? = null,
    val startsOn: LocalDate? = null,
    val endsOn: LocalDate? = null,
    val notes: String? = null,
    /** On create: tasks to assign. Defaults to the account's default tasks. */
    val taskIds: List<UUID>? = null,
    /** On create: people to assign (the creator is always assigned as a manager). */
    val membershipIds: List<UUID>? = null,
)

data class ProjectTaskInput(
    val taskId: UUID? = null,
    val billable: Boolean? = null,
    val hourlyRate: Long? = null,
    val budgetSeconds: Long? = null,
    val budgetAmount: Long? = null,
    val isActive: Boolean? = null,
)

data class ProjectMemberInput(
    val membershipId: UUID? = null,
    val isManager: Boolean? = null,
    val useDefaultRates: Boolean? = null,
    val hourlyRate: Long? = null,
    val budgetSeconds: Long? = null,
    val isActive: Boolean? = null,
)

data class AssignmentTask(val taskId: UUID, val name: String, val billable: Boolean)

/** What the caller can track time against (Harvest: /users/me/project_assignments). */
data class MyAssignment(
    val projectId: UUID,
    val projectName: String,
    val projectCode: String?,
    val client: ClientRef,
    val isManager: Boolean,
    val isBillable: Boolean,
    val tasks: List<AssignmentTask>,
)

private val BILL_BY = setOf("project", "tasks", "people", "none")
private val BUDGET_BY = setOf("project", "project_cost", "task", "task_fees", "person", "none")

@Service
class ProjectService(private val dsl: DSLContext, private val access: Access) {

    @Transactional(readOnly = true)
    fun list(m: Member, isActive: Boolean?, clientId: UUID?, cursor: String?, limit: Int?): Page<ProjectView> {
        m.requireManagerOrAdmin()
        val n = clampLimit(limit, 500)
        val managed = access.managedProjectIds(m)
        val rows = dsl.selectFrom(PROJECTS)
            .where(if (managed != null) PROJECTS.ID.`in`(managed) else DSL.noCondition())
            .and(if (isActive != null) PROJECTS.IS_ACTIVE.eq(isActive) else DSL.noCondition())
            .and(if (clientId != null) PROJECTS.CLIENT_ID.eq(clientId) else DSL.noCondition())
            .and(if (cursor != null) PROJECTS.ID.gt(idCursor(cursor)) else DSL.noCondition())
            .orderBy(PROJECTS.ID).limit(n + 1).fetch()
        return Page.of(views(rows), n) { it.id.toString() }
    }

    @Transactional(readOnly = true)
    fun get(m: Member, id: UUID): ProjectView {
        access.requireManages(m, id)
        return views(listOf(load(id))).single()
    }

    @Transactional
    fun create(m: Member, input: ProjectInput): ProjectView {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        val errors = mutableMapOf<String, String>()
        if (input.clientId == null) errors["client_id"] = "Choose a client"
        if (input.name.isNullOrBlank()) errors["name"] = "Enter a project name"
        if (errors.isNotEmpty()) throw ValidationException(errors)
        dsl.fetchExists(CLIENTS, CLIENTS.ID.eq(input.clientId)).also { if (!it) throw ValidationException("client_id", "Unknown client") }

        val r = dsl.newRecord(PROJECTS).apply {
            accountId = m.accountId
            clientId = input.clientId
        }
        applyInput(m, r, Patch.all(input, ProjectInput::class.java.declaredFields.map { snake(it.name) }.toSet()))
        store(r)

        val taskIds = input.taskIds ?: dsl.select(TASKS.ID).from(TASKS).where(TASKS.IS_DEFAULT.isTrue).and(TASKS.IS_ACTIVE.isTrue).fetch(TASKS.ID)
        taskIds.distinct().forEach { taskId -> addTaskRecord(m, r, ProjectTaskInput(taskId = taskId)) }

        // The creator manages the project; people with access to all future projects are added too.
        addMemberRecord(r.id, m.accountId, m.membershipId, ProjectMemberInput(isManager = m.role != Role.MEMBER))
        val auto = dsl.select(MEMBERSHIPS.ID).from(MEMBERSHIPS)
            .where(MEMBERSHIPS.HAS_ACCESS_TO_ALL_FUTURE_PROJECTS.isTrue).and(MEMBERSHIPS.IS_ACTIVE.isTrue).fetch(MEMBERSHIPS.ID)
        access.requireInAccount(MEMBERSHIPS, input.membershipIds.orEmpty(), "membership_ids", "person")
        (auto + input.membershipIds.orEmpty()).distinct().filter { it != m.membershipId }.forEach { id ->
            addMemberRecord(r.id, m.accountId, id, ProjectMemberInput())
        }
        return views(listOf(r)).single()
    }

    @Transactional
    fun update(m: Member, id: UUID, patch: Patch<ProjectInput>): ProjectView {
        m.requireWritable()
        access.requireManages(m, id)
        val r = load(id)
        ETags.checkIfMatch(r.updatedAt)
        patch.value.clientId?.let {
            if (!dsl.fetchExists(CLIENTS, CLIENTS.ID.eq(it))) throw ValidationException("client_id", "Unknown client")
            r.clientId = it
        }
        applyInput(m, r, patch)
        store(r)
        return views(listOf(r)).single()
    }

    @Transactional
    fun delete(m: Member, id: UUID) {
        m.requireWritable()
        access.requireManages(m, id)
        val r = load(id)
        ETags.checkIfMatch(r.updatedAt)
        if (dsl.fetchExists(TIME_ENTRIES, TIME_ENTRIES.PROJECT_ID.eq(id)) || dsl.fetchExists(EXPENSES, EXPENSES.PROJECT_ID.eq(id))) {
            throw ConflictException("project_in_use", "This project has tracked time or expenses. Archive it instead.")
        }
        r.delete()
    }

    // ---- task assignments ------------------------------------------------

    @Transactional
    fun addTask(m: Member, projectId: UUID, input: ProjectTaskInput): ProjectView {
        m.requireWritable()
        access.requireManages(m, projectId)
        val p = load(projectId)
        if (input.taskId == null) throw ValidationException("task_id", "Choose a task")
        if (dsl.fetchExists(PROJECT_TASKS, PROJECT_TASKS.PROJECT_ID.eq(projectId).and(PROJECT_TASKS.TASK_ID.eq(input.taskId)))) {
            throw ValidationException("task_id", "This task is already on the project")
        }
        addTaskRecord(m, p, input)
        return views(listOf(p)).single()
    }

    @Transactional
    fun updateTask(m: Member, projectId: UUID, projectTaskId: UUID, patch: Patch<ProjectTaskInput>): ProjectView {
        m.requireWritable()
        access.requireManages(m, projectId)
        val pt = dsl.selectFrom(PROJECT_TASKS).where(PROJECT_TASKS.ID.eq(projectTaskId)).and(PROJECT_TASKS.PROJECT_ID.eq(projectId)).fetchOne()
            ?: throw NotFoundException("Project task")
        applyTask(m, pt, patch)
        pt.store()
        return views(listOf(load(projectId))).single()
    }

    @Transactional
    fun removeTask(m: Member, projectId: UUID, projectTaskId: UUID): ProjectView {
        m.requireWritable()
        access.requireManages(m, projectId)
        val pt = dsl.selectFrom(PROJECT_TASKS).where(PROJECT_TASKS.ID.eq(projectTaskId)).and(PROJECT_TASKS.PROJECT_ID.eq(projectId)).fetchOne()
            ?: throw NotFoundException("Project task")
        if (dsl.fetchExists(TIME_ENTRIES, TIME_ENTRIES.PROJECT_ID.eq(projectId).and(TIME_ENTRIES.TASK_ID.eq(pt.taskId)))) {
            throw ConflictException("task_in_use", "Time was tracked to this task. Deactivate it instead.")
        }
        pt.delete()
        return views(listOf(load(projectId))).single()
    }

    // ---- people assignments ----------------------------------------------

    @Transactional
    fun addMember(m: Member, projectId: UUID, input: ProjectMemberInput): ProjectView {
        m.requireWritable()
        access.requireManages(m, projectId)
        val membershipId = input.membershipId ?: throw ValidationException("membership_id", "Choose a person")
        if (!dsl.fetchExists(MEMBERSHIPS, MEMBERSHIPS.ID.eq(membershipId))) throw ValidationException("membership_id", "Unknown person")
        if (dsl.fetchExists(PROJECT_MEMBERS, PROJECT_MEMBERS.PROJECT_ID.eq(projectId).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(membershipId)))) {
            throw ValidationException("membership_id", "This person is already on the project")
        }
        val pm = addMemberRecord(projectId, m.accountId, membershipId, ProjectMemberInput())
        applyMember(m, pm, Patch.all(input, setOf("is_manager", "use_default_rates", "hourly_rate", "budget_seconds", "is_active").filterTo(mutableSetOf()) { f ->
            when (f) {
                "is_manager" -> input.isManager != null
                "use_default_rates" -> input.useDefaultRates != null
                "hourly_rate" -> input.hourlyRate != null
                "budget_seconds" -> input.budgetSeconds != null
                else -> input.isActive != null
            }
        }))
        pm.store()
        return views(listOf(load(projectId))).single()
    }

    @Transactional
    fun updateMember(m: Member, projectId: UUID, projectMemberId: UUID, patch: Patch<ProjectMemberInput>): ProjectView {
        m.requireWritable()
        access.requireManages(m, projectId)
        val pm = dsl.selectFrom(PROJECT_MEMBERS).where(PROJECT_MEMBERS.ID.eq(projectMemberId)).and(PROJECT_MEMBERS.PROJECT_ID.eq(projectId)).fetchOne()
            ?: throw NotFoundException("Project member")
        applyMember(m, pm, patch)
        pm.store()
        return views(listOf(load(projectId))).single()
    }

    @Transactional
    fun removeMember(m: Member, projectId: UUID, projectMemberId: UUID): ProjectView {
        m.requireWritable()
        access.requireManages(m, projectId)
        val pm = dsl.selectFrom(PROJECT_MEMBERS).where(PROJECT_MEMBERS.ID.eq(projectMemberId)).and(PROJECT_MEMBERS.PROJECT_ID.eq(projectId)).fetchOne()
            ?: throw NotFoundException("Project member")
        if (dsl.fetchExists(TIME_ENTRIES, TIME_ENTRIES.PROJECT_ID.eq(projectId).and(TIME_ENTRIES.MEMBERSHIP_ID.eq(pm.membershipId)))) {
            // Keep the assignment so history stays attached; just deactivate it.
            pm.isActive = false
            pm.store()
        } else {
            pm.delete()
        }
        return views(listOf(load(projectId))).single()
    }

    // ---- my assignments ---------------------------------------------------

    @Transactional(readOnly = true)
    fun myAssignments(m: Member): List<MyAssignment> {
        val rows = dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.CODE, PROJECTS.IS_BILLABLE, PROJECT_MEMBERS.IS_MANAGER, CLIENTS.ID, CLIENTS.NAME, CLIENTS.CURRENCY)
            .from(PROJECT_MEMBERS)
            .join(PROJECTS).on(PROJECTS.ID.eq(PROJECT_MEMBERS.PROJECT_ID))
            .join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .where(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(m.membershipId))
            .and(PROJECT_MEMBERS.IS_ACTIVE.isTrue).and(PROJECTS.IS_ACTIVE.isTrue)
            .orderBy(CLIENTS.NAME, PROJECTS.NAME)
            .fetch()
        val tasks = dsl.select(PROJECT_TASKS.PROJECT_ID, TASKS.ID, TASKS.NAME, PROJECT_TASKS.BILLABLE)
            .from(PROJECT_TASKS).join(TASKS).on(TASKS.ID.eq(PROJECT_TASKS.TASK_ID))
            .where(PROJECT_TASKS.PROJECT_ID.`in`(rows.map { it[PROJECTS.ID] }))
            .and(PROJECT_TASKS.IS_ACTIVE.isTrue).and(TASKS.IS_ACTIVE.isTrue)
            .orderBy(TASKS.NAME)
            .fetch().groupBy({ it[PROJECT_TASKS.PROJECT_ID] }) { AssignmentTask(it[TASKS.ID], it[TASKS.NAME], it[PROJECT_TASKS.BILLABLE]) }
        return rows.map {
            MyAssignment(
                it[PROJECTS.ID], it[PROJECTS.NAME], it[PROJECTS.CODE], ClientRef(it[CLIENTS.ID], it[CLIENTS.NAME], it[CLIENTS.CURRENCY]),
                it[PROJECT_MEMBERS.IS_MANAGER], it[PROJECTS.IS_BILLABLE], tasks[it[PROJECTS.ID]].orEmpty(),
            )
        }
    }

    // ---- internals ---------------------------------------------------------

    fun load(id: UUID): ProjectsRecord = dsl.selectFrom(PROJECTS).where(PROJECTS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Project")

    private fun store(r: ProjectsRecord) {
        try {
            r.store()
        } catch (e: DuplicateKeyException) {
            throw ValidationException("name", "This client already has a project with that name")
        }
    }

    private fun applyInput(m: Member, r: ProjectsRecord, p: Patch<ProjectInput>) {
        val i = p.value
        val errors = mutableMapOf<String, String>()
        i.name?.let { if (it.isBlank()) errors["name"] = "Enter a project name" else r.name = it.trim() }
        p.field("code", { code }) { r.code = it?.ifBlank { null }?.trim() }
        i.isActive?.let {
            r.isActive = it
            r.archivedAt = if (it) null else (r.archivedAt ?: Instant.now())
        }
        i.isBillable?.let { r.isBillable = it }
        i.billBy?.let { if (it !in BILL_BY) errors["bill_by"] = "Must be one of $BILL_BY" else r.billBy = it }
        i.budgetBy?.let { if (it !in BUDGET_BY) errors["budget_by"] = "Must be one of $BUDGET_BY" else r.budgetBy = it }
        i.budgetIsMonthly?.let { r.budgetIsMonthly = it }
        p.field("budget_seconds", { budgetSeconds }) { if (it != null && it < 0) errors["budget_seconds"] = "Must not be negative" else r.budgetSeconds = it }
        i.budgetIncludeExpenses?.let { r.budgetIncludeExpenses = it }
        p.field("budget_alert_percent", { budgetAlertPercent }) {
            if (it != null && (it <= BigDecimal.ZERO || it > BigDecimal(100))) errors["budget_alert_percent"] = "Use a percentage between 0 and 100"
            else r.budgetAlertPercent = it
        }
        i.notifyWhenOverBudget?.let { r.notifyWhenOverBudget = it }
        i.showBudgetToAll?.let { r.showBudgetToAll = it }
        p.field("starts_on", { startsOn }) { r.startsOn = it }
        p.field("ends_on", { endsOn }) { r.endsOn = it }
        if (r.startsOn != null && r.endsOn != null && r.endsOn.isBefore(r.startsOn)) errors["ends_on"] = "The end date is before the start date"
        p.field("notes", { notes }) { r.notes = it?.ifBlank { null } }
        i.isFixedFee?.let { r.isFixedFee = it }
        // Money fields need rate visibility; others silently keep their value.
        if (m.canSeeRates) {
            p.field("hourly_rate", { hourlyRate }) { if (it != null && it < 0) errors["hourly_rate"] = "Rates cannot be negative" else r.hourlyRate = it }
            p.field("fee_amount", { feeAmount }) { if (it != null && it < 0) errors["fee_amount"] = "Must not be negative" else r.feeAmount = it }
            p.field("budget_amount", { budgetAmount }) { if (it != null && it < 0) errors["budget_amount"] = "Must not be negative" else r.budgetAmount = it }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    private fun addTaskRecord(m: Member, p: ProjectsRecord, input: ProjectTaskInput): ProjectTasksRecord {
        val task = dsl.selectFrom(TASKS).where(TASKS.ID.eq(input.taskId)).fetchOne() ?: throw ValidationException("task_id", "Unknown task")
        val pt = dsl.newRecord(PROJECT_TASKS).apply {
            accountId = p.accountId
            projectId = p.id
            taskId = task.id
            billable = input.billable ?: (task.defaultBillable && p.isBillable)
            hourlyRate = if (p.billBy == "tasks") task.defaultRate else null
        }
        applyTask(m, pt, Patch.all(input, buildSet {
            if (input.hourlyRate != null) add("hourly_rate")
            if (input.budgetSeconds != null) add("budget_seconds")
            if (input.budgetAmount != null) add("budget_amount")
            if (input.isActive != null) add("is_active")
        }))
        pt.store()
        return pt
    }

    private fun applyTask(m: Member, pt: ProjectTasksRecord, p: Patch<ProjectTaskInput>) {
        p.value.billable?.let { pt.billable = it }
        p.value.isActive?.let { pt.isActive = it }
        p.field("budget_seconds", { budgetSeconds }) { pt.budgetSeconds = it?.also { v -> if (v < 0) throw ValidationException("budget_seconds", "Must not be negative") } }
        if (m.canSeeRates) {
            p.field("hourly_rate", { hourlyRate }) { pt.hourlyRate = it?.also { v -> if (v < 0) throw ValidationException("hourly_rate", "Rates cannot be negative") } }
            p.field("budget_amount", { budgetAmount }) { pt.budgetAmount = it?.also { v -> if (v < 0) throw ValidationException("budget_amount", "Must not be negative") } }
        }
    }

    private fun addMemberRecord(projectId: UUID, accountId: UUID, membershipId: UUID, input: ProjectMemberInput): ProjectMembersRecord {
        val pm = dsl.newRecord(PROJECT_MEMBERS).apply {
            this.accountId = accountId
            this.projectId = projectId
            this.membershipId = membershipId
            isManager = input.isManager ?: false
        }
        pm.store()
        return pm
    }

    private fun applyMember(m: Member, pm: ProjectMembersRecord, p: Patch<ProjectMemberInput>) {
        p.value.isManager?.let {
            val role = dsl.select(MEMBERSHIPS.ROLE).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(pm.membershipId)).fetchOne()!!.value1()
            if (it && role == "member") throw ValidationException("is_manager", "Only managers and admins can manage projects. Change their role first.")
            if (!m.isAdmin && pm.membershipId == m.membershipId && !it) throw ForbiddenException("You can't remove your own manager access")
            pm.isManager = it
        }
        p.value.isActive?.let { pm.isActive = it }
        p.value.useDefaultRates?.let { pm.useDefaultRates = it }
        p.field("budget_seconds", { budgetSeconds }) { pm.budgetSeconds = it?.also { v -> if (v < 0) throw ValidationException("budget_seconds", "Must not be negative") } }
        if (m.canSeeRates) {
            p.field("hourly_rate", { hourlyRate }) {
                pm.hourlyRate = it?.also { v -> if (v < 0) throw ValidationException("hourly_rate", "Rates cannot be negative") }
                if (it != null && !p.has("use_default_rates")) pm.useDefaultRates = false
            }
        }
    }

    private fun views(projects: List<ProjectsRecord>): List<ProjectView> {
        if (projects.isEmpty()) return emptyList()
        val ids = projects.map { it.id }
        val clients = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.`in`(projects.map { it.clientId })).fetchMap(CLIENTS.ID)
        val tasks = dsl.select(PROJECT_TASKS.asterisk(), TASKS.NAME).from(PROJECT_TASKS).join(TASKS).on(TASKS.ID.eq(PROJECT_TASKS.TASK_ID))
            .where(PROJECT_TASKS.PROJECT_ID.`in`(ids)).orderBy(TASKS.NAME)
            .fetch().groupBy({ it[PROJECT_TASKS.PROJECT_ID] }) {
                ProjectTaskView(
                    it[PROJECT_TASKS.ID], it[PROJECT_TASKS.TASK_ID], it[TASKS.NAME], it[PROJECT_TASKS.BILLABLE], it[PROJECT_TASKS.HOURLY_RATE],
                    it[PROJECT_TASKS.BUDGET_SECONDS], it[PROJECT_TASKS.BUDGET_AMOUNT], it[PROJECT_TASKS.IS_ACTIVE],
                )
            }
        val members = dsl.select(PROJECT_MEMBERS.asterisk(), MEMBERSHIPS.NAME).from(PROJECT_MEMBERS).join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(PROJECT_MEMBERS.MEMBERSHIP_ID))
            .where(PROJECT_MEMBERS.PROJECT_ID.`in`(ids)).orderBy(MEMBERSHIPS.NAME)
            .fetch().groupBy({ it[PROJECT_MEMBERS.PROJECT_ID] }) {
                ProjectMemberView(
                    it[PROJECT_MEMBERS.ID], it[PROJECT_MEMBERS.MEMBERSHIP_ID], it[MEMBERSHIPS.NAME], it[PROJECT_MEMBERS.IS_MANAGER],
                    it[PROJECT_MEMBERS.USE_DEFAULT_RATES], it[PROJECT_MEMBERS.HOURLY_RATE], it[PROJECT_MEMBERS.BUDGET_SECONDS], it[PROJECT_MEMBERS.IS_ACTIVE],
                )
            }
        return projects.map { r ->
            val c = clients.getValue(r.clientId)
            ProjectView(
                id = r.id, client = ClientRef(c.id, c.name, c.currency), name = r.name, code = r.code, isActive = r.isActive,
                isBillable = r.isBillable, isFixedFee = r.isFixedFee, billBy = r.billBy, hourlyRate = r.hourlyRate, feeAmount = r.feeAmount,
                budgetBy = r.budgetBy, budgetIsMonthly = r.budgetIsMonthly, budgetSeconds = r.budgetSeconds, budgetAmount = r.budgetAmount,
                budgetIncludeExpenses = r.budgetIncludeExpenses, budgetAlertPercent = r.budgetAlertPercent,
                notifyWhenOverBudget = r.notifyWhenOverBudget, showBudgetToAll = r.showBudgetToAll, startsOn = r.startsOn, endsOn = r.endsOn,
                notes = r.notes, tasks = tasks[r.id].orEmpty(), members = members[r.id].orEmpty(), createdAt = r.createdAt, updatedAt = r.updatedAt,
            )
        }
    }

    private fun snake(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "projects")
class ProjectController(private val projects: ProjectService, private val patches: Patches) {
    @GetMapping
    fun list(
        @RequestParam(name = "is_active", required = false) isActive: Boolean?,
        @RequestParam(name = "client_id", required = false) clientId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = projects.list(Current.member(), isActive, clientId, cursor, limit)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = projects.get(Current.member(), id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: ProjectInput) = projects.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @DocBody(content = [Content(schema = Schema(implementation = ProjectInput::class))]) @RequestBody body: JsonNode,
    ) = projects.update(Current.member(), id, patches.parse(body))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = projects.delete(Current.member(), id)

    @PostMapping("/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    fun addTask(@PathVariable id: UUID, @RequestBody body: ProjectTaskInput) = projects.addTask(Current.member(), id, body)

    @PatchMapping("/{id}/tasks/{projectTaskId}")
    fun updateTask(
        @PathVariable id: UUID,
        @PathVariable projectTaskId: UUID,
        @DocBody(content = [Content(schema = Schema(implementation = ProjectTaskInput::class))]) @RequestBody body: JsonNode,
    ) = projects.updateTask(Current.member(), id, projectTaskId, patches.parse(body))

    @DeleteMapping("/{id}/tasks/{projectTaskId}")
    fun removeTask(@PathVariable id: UUID, @PathVariable projectTaskId: UUID) = projects.removeTask(Current.member(), id, projectTaskId)

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(@PathVariable id: UUID, @RequestBody body: ProjectMemberInput) = projects.addMember(Current.member(), id, body)

    @PatchMapping("/{id}/members/{projectMemberId}")
    fun updateMember(
        @PathVariable id: UUID,
        @PathVariable projectMemberId: UUID,
        @DocBody(content = [Content(schema = Schema(implementation = ProjectMemberInput::class))]) @RequestBody body: JsonNode,
    ) = projects.updateMember(Current.member(), id, projectMemberId, patches.parse(body))

    @DeleteMapping("/{id}/members/{projectMemberId}")
    fun removeMember(@PathVariable id: UUID, @PathVariable projectMemberId: UUID) = projects.removeMember(Current.member(), id, projectMemberId)
}

@RestController
@RequestMapping("/api/v1/me/assignments")
@Tag(name = "me")
class MyAssignmentsController(private val projects: ProjectService) {
    /** Projects and tasks the caller can track time to. */
    @GetMapping
    fun list() = projects.myAssignments(Current.member())
}
