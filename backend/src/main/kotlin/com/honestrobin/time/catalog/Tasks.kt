// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.platform.web.idCursor
import com.honestrobin.time.accounts.Access
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.tables.records.TasksRecord
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ETags
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Page
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.platform.web.Versioned
import com.honestrobin.time.platform.web.Views
import com.honestrobin.time.platform.web.clampLimit
import com.fasterxml.jackson.annotation.JsonView
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
import java.time.Instant
import java.util.UUID

data class TaskView(
    val id: UUID,
    val name: String,
    /** Added to new projects automatically. */
    val isDefault: Boolean,
    val defaultBillable: Boolean,
    /** Default hourly rate in minor units, used when a project bills by task. */
    @JsonView(Views.Rates::class) val defaultRate: Long?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class TaskInput(
    val name: String? = null,
    val isDefault: Boolean? = null,
    val defaultBillable: Boolean? = null,
    val defaultRate: Long? = null,
    val isActive: Boolean? = null,
)

@Service
class TaskService(private val dsl: DSLContext, private val access: Access) {

    @Transactional(readOnly = true)
    fun list(m: Member, isActive: Boolean?, cursor: String?, limit: Int?): Page<TaskView> {
        m.requireManagerOrAdmin()
        val n = clampLimit(limit, 500)
        val rows = dsl.selectFrom(TASKS)
            .where(if (isActive != null) TASKS.IS_ACTIVE.eq(isActive) else DSL.noCondition())
            .and(if (cursor != null) TASKS.ID.gt(idCursor(cursor)) else DSL.noCondition())
            .orderBy(TASKS.ID).limit(n + 1).fetch().map(::view)
        return Page.of(rows, n) { it.id.toString() }
    }

    @Transactional(readOnly = true)
    fun get(m: Member, id: UUID): TaskView {
        m.requireManagerOrAdmin()
        return view(load(id))
    }

    @Transactional
    fun create(m: Member, input: TaskInput): TaskView {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        if (input.name.isNullOrBlank()) throw ValidationException("name", "Enter a task name")
        val r = dsl.newRecord(TASKS).apply { accountId = m.accountId }
        apply(m, r, input)
        store(r)
        return view(r)
    }

    @Transactional
    fun update(m: Member, id: UUID, input: TaskInput): TaskView {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        val r = load(id)
        ETags.checkIfMatch(r.updatedAt)
        apply(m, r, input)
        store(r)
        return view(r)
    }

    @Transactional
    fun delete(m: Member, id: UUID) {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        val r = load(id)
        if (dsl.fetchExists(PROJECT_TASKS, PROJECT_TASKS.TASK_ID.eq(id))) {
            throw ConflictException("task_in_use", "This task is used on projects. Archive it instead.")
        }
        r.delete()
    }

    fun load(id: UUID): TasksRecord = dsl.selectFrom(TASKS).where(TASKS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Task")

    private fun store(r: TasksRecord) {
        try {
            r.store()
        } catch (e: DuplicateKeyException) {
            throw ValidationException("name", "A task with this name already exists")
        }
    }

    private fun apply(m: Member, r: TasksRecord, i: TaskInput) {
        i.name?.let { if (it.isBlank()) throw ValidationException("name", "Enter a task name") else r.name = it.trim() }
        i.isDefault?.let { r.isDefault = it }
        i.defaultBillable?.let { r.defaultBillable = it }
        if (i.defaultRate != null && m.canSeeRates) {
            if (i.defaultRate < 0) throw ValidationException("default_rate", "Rates cannot be negative")
            r.defaultRate = i.defaultRate
        }
        i.isActive?.let {
            r.isActive = it
            r.archivedAt = if (it) null else (r.archivedAt ?: Instant.now())
        }
    }

    companion object {
        fun view(r: TasksRecord) = TaskView(r.id, r.name, r.isDefault, r.defaultBillable, r.defaultRate, r.isActive, r.createdAt, r.updatedAt)
    }
}

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "tasks", description = "The account-wide task list")
class TaskController(private val tasks: TaskService) {
    @GetMapping
    fun list(
        @RequestParam(name = "is_active", required = false) isActive: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = tasks.list(Current.member(), isActive, cursor, limit)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = tasks.get(Current.member(), id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: TaskInput) = tasks.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody body: TaskInput) = tasks.update(Current.member(), id, body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = tasks.delete(Current.member(), id)
}
