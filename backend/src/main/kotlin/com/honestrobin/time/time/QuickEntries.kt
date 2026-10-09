// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.catalog.ClientInput
import com.honestrobin.time.catalog.ClientService
import com.honestrobin.time.catalog.ProjectInput
import com.honestrobin.time.catalog.ProjectService
import com.honestrobin.time.catalog.ProjectTaskInput
import com.honestrobin.time.catalog.TaskInput
import com.honestrobin.time.catalog.TaskService
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * "Enter as you go": a time entry together with whatever it still lacks, a new project, its client
 * or a task. Names that already exist are reused, ignoring case.
 */
data class QuickEntryInput(
    /** An existing project, or none to create one named [projectName]. */
    val projectId: UUID? = null,
    val projectName: String? = null,
    /** For a new project: an existing client, or none to use (or create) the one named [clientName]. */
    val clientId: UUID? = null,
    val clientName: String? = null,
    /** For a new project: its hourly rate in minor units, or none to leave it without one. */
    val hourlyRate: Long? = null,
    /** An existing task, or none to use (or create) the one named [taskName] and put it on the project. */
    val taskId: UUID? = null,
    val taskName: String? = null,
    val spentDate: LocalDate? = null,
    /** Omit it to start a timer, as with a plain entry. */
    val durationSeconds: Int? = null,
    val notes: String? = null,
)

/**
 * Creates everything in one transaction: when any part is refused, nothing is kept, and the error
 * names the field the person typed, so the form can show it there.
 */
@Service
class QuickEntryService(
    private val dsl: DSLContext,
    private val clients: ClientService,
    private val tasks: TaskService,
    private val projects: ProjectService,
    private val entries: TimeEntryService,
) {
    @Transactional
    fun create(m: Member, input: QuickEntryInput): TimeEntryView {
        validate(input)
        val typedTask = input.taskName?.trim().orEmpty()
        var taskId = input.taskId
        val projectId: UUID
        if (input.projectId == null) {
            if (taskId == null && typedTask.isNotEmpty()) taskId = findOrCreateTask(m, typedTask)
            val clientId = input.clientId ?: findOrCreateClient(m, input.clientName!!.trim())
            // A new project gets the account's default tasks, as it would from the projects page,
            // plus the one chosen here.
            val defaults = dsl.select(TASKS.ID).from(TASKS).where(TASKS.IS_DEFAULT.isTrue).and(TASKS.IS_ACTIVE.isTrue).fetch(TASKS.ID)
            val taskIds = (defaults + listOfNotNull(taskId)).distinct()
            val project = step(mapOf("name" to "project_name")) {
                projects.create(m, ProjectInput(clientId = clientId, name = input.projectName!!.trim(), hourlyRate = input.hourlyRate, taskIds = taskIds))
            }
            projectId = project.id
            if (taskId == null) taskId = project.tasks.firstOrNull()?.taskId ?: throw ValidationException("task_name", "Enter a task name")
        } else {
            projectId = input.projectId
            if (taskId == null && typedTask.isNotEmpty()) {
                taskId = taskOnProject(projectId, typedTask) ?: findOrCreateTask(m, typedTask).also { id ->
                    step(mapOf("task_id" to "task_name")) { projects.addTask(m, projectId, ProjectTaskInput(taskId = id)) }
                }
            }
        }
        return entries.create(
            m,
            TimeEntryInput(
                projectId = projectId,
                taskId = taskId ?: throw ValidationException("task_id", "Choose a task"),
                spentDate = input.spentDate,
                durationSeconds = input.durationSeconds,
                notes = input.notes,
            ),
        )
    }

    /** Everything that can be checked before anything is written, so all of it shows at once. */
    private fun validate(i: QuickEntryInput) {
        val errors = mutableMapOf<String, String>()
        if (i.projectId == null) {
            if (i.projectName.isNullOrBlank()) errors["project_name"] = "Enter a project name"
            if (i.clientId == null && i.clientName.isNullOrBlank()) errors["client_name"] = "Enter a client name"
            if (i.hourlyRate != null && i.hourlyRate < 0) errors["hourly_rate"] = "Rates cannot be negative"
        }
        i.durationSeconds?.let {
            if (it < 0) errors["duration_seconds"] = "Time can't be negative"
            if (it > MAX_ENTRY_SECONDS) errors["duration_seconds"] = "An entry can't be longer than 24 hours"
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    private fun findOrCreateClient(m: Member, name: String): UUID =
        dsl.select(CLIENTS.ID).from(CLIENTS).where(DSL.lower(CLIENTS.NAME).eq(name.lowercase())).and(CLIENTS.IS_ACTIVE.isTrue).fetchOne()?.value1()
            ?: step(emptyMap(), fallback = "client_name") { clients.create(m, ClientInput(name = name)) }.id

    private fun findOrCreateTask(m: Member, name: String): UUID =
        dsl.select(TASKS.ID).from(TASKS).where(DSL.lower(TASKS.NAME).eq(name.lowercase())).and(TASKS.IS_ACTIVE.isTrue).fetchOne()?.value1()
            // The account's first task becomes a default one, so later projects get it too.
            ?: step(emptyMap(), fallback = "task_name") { tasks.create(m, TaskInput(name = name, isDefault = !dsl.fetchExists(TASKS))) }.id

    private fun taskOnProject(projectId: UUID, name: String): UUID? =
        dsl.select(PROJECT_TASKS.TASK_ID).from(PROJECT_TASKS).join(TASKS).on(TASKS.ID.eq(PROJECT_TASKS.TASK_ID))
            .where(PROJECT_TASKS.PROJECT_ID.eq(projectId)).and(DSL.lower(TASKS.NAME).eq(name.lowercase()))
            .fetchOne()?.value1()

    /** Runs one step, renaming the fields of a refusal to the ones the person typed in. */
    private inline fun <T> step(rename: Map<String, String>, fallback: String? = null, block: () -> T): T =
        try {
            block()
        } catch (e: ValidationException) {
            throw ValidationException(e.fields.mapKeys { (k, _) -> rename[k] ?: fallback ?: k }, e.message)
        }
}

@RestController
@RequestMapping("/api/v1/time_entries")
@Tag(name = "time_entries", description = "Time entries and timers")
class QuickEntryController(private val quick: QuickEntryService) {
    /** Creates an entry together with a new project, client or task: all of it, or none of it. */
    @PostMapping("/quick")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: QuickEntryInput) = quick.create(Current.member(), body)
}
