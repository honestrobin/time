// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.expenses

import com.honestrobin.time.platform.web.dateIdCursor
import com.honestrobin.time.accounts.Access
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.budgets.BudgetAlertService
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.EXPENSE_CATEGORIES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.tables.records.ExpenseCategoriesRecord
import com.honestrobin.time.db.tables.records.ExpensesRecord
import com.honestrobin.time.files.FileService
import com.honestrobin.time.files.FileTypes
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
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
import com.honestrobin.time.time.ProjectRef
import com.honestrobin.time.time.Ref
import com.fasterxml.jackson.annotation.JsonView
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ExpenseCategoryView(
    val id: UUID,
    val name: String,
    val unitName: String?,
    /** Price per unit in minor units (e.g. mileage per km). Null for free-amount categories. */
    @JsonView(Views.Rates::class) val unitPrice: Long?,
    /** Whether expenses in this category are entered as units (the amount is calculated). */
    val isUnitPriced: Boolean,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class ExpenseCategoryInput(val name: String? = null, val unitName: String? = null, val unitPrice: Long? = null, val isActive: Boolean? = null)

data class ExpenseView(
    val id: UUID,
    val spentDate: LocalDate,
    val person: Ref,
    val client: Ref,
    val project: ProjectRef,
    val category: Ref,
    /**
     * What was spent, in minor units. Visible to whoever can see the expense (it is the person's own
     * spend, not a billing rate; see ADR 0008).
     */
    @JsonView(Views.Public::class) val amount: Long,
    val currency: String,
    val units: BigDecimal?,
    val notes: String?,
    val billable: Boolean,
    val hasReceipt: Boolean,
    val receiptFilename: String?,
    val approvalState: String,
    val isLocked: Boolean,
    val invoiceId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class ExpenseInput(
    val membershipId: UUID? = null,
    val projectId: UUID? = null,
    val categoryId: UUID? = null,
    val spentDate: LocalDate? = null,
    /** Minor units; required unless the category is priced per unit. */
    val amount: Long? = null,
    val units: BigDecimal? = null,
    val notes: String? = null,
    val billable: Boolean? = null,
)

@Service
class ExpenseCategoryService(private val dsl: DSLContext) {
    @Transactional(readOnly = true)
    fun list(isActive: Boolean?): List<ExpenseCategoryView> =
        dsl.selectFrom(EXPENSE_CATEGORIES).where(if (isActive != null) EXPENSE_CATEGORIES.IS_ACTIVE.eq(isActive) else DSL.noCondition())
            .orderBy(EXPENSE_CATEGORIES.NAME).fetch(::view)

    @Transactional
    fun create(m: Member, input: ExpenseCategoryInput): ExpenseCategoryView {
        m.requireWritable()
        m.requireAdmin()
        if (input.name.isNullOrBlank()) throw ValidationException("name", "Enter a category name")
        val r = dsl.newRecord(EXPENSE_CATEGORIES).apply { accountId = m.accountId }
        apply(r, Patch.all(input, if (input.unitPrice != null) setOf("unit_price", "unit_name") else setOf("unit_name")))
        store(r)
        return view(r)
    }

    @Transactional
    fun update(m: Member, id: UUID, patch: Patch<ExpenseCategoryInput>): ExpenseCategoryView {
        m.requireWritable()
        m.requireAdmin()
        val r = dsl.selectFrom(EXPENSE_CATEGORIES).where(EXPENSE_CATEGORIES.ID.eq(id)).fetchOne() ?: throw NotFoundException("Expense category")
        ETags.checkIfMatch(r.updatedAt)
        apply(r, patch)
        store(r)
        return view(r)
    }

    private fun apply(r: ExpenseCategoriesRecord, p: Patch<ExpenseCategoryInput>) {
        p.value.name?.let { if (it.isBlank()) throw ValidationException("name", "Enter a category name") else r.name = it.trim() }
        p.field("unit_name", { unitName }) { r.unitName = it?.ifBlank { null } }
        p.field("unit_price", { unitPrice }) { r.unitPrice = it?.also { v -> if (v < 0) throw ValidationException("unit_price", "Must not be negative") } }
        if ((r.unitPrice == null) != (r.unitName == null)) throw ValidationException("unit_name", "Unit-priced categories need both a unit name and a price")
        p.value.isActive?.let {
            r.isActive = it
            r.archivedAt = if (it) null else (r.archivedAt ?: Instant.now())
        }
    }

    private fun store(r: ExpenseCategoriesRecord) {
        try {
            r.store()
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            throw ValidationException("name", "A category with this name already exists")
        }
    }

    companion object {
        fun view(r: ExpenseCategoriesRecord) = ExpenseCategoryView(r.id, r.name, r.unitName, r.unitPrice, r.unitPrice != null, r.isActive, r.createdAt, r.updatedAt)
    }
}

@Service
class ExpenseService(
    private val dsl: DSLContext,
    private val access: Access,
    private val accounts: AccountSettingsRepository,
    private val files: FileService,
    private val budgets: BudgetAlertService,
    private val clock: java.time.Clock,
) {
    @Transactional(readOnly = true)
    fun list(m: Member, from: LocalDate?, to: LocalDate?, membershipId: UUID?, projectId: UUID?, cursor: String?, limit: Int?): Page<ExpenseView> {
        val n = clampLimit(limit, 200)
        val seek = cursor?.let { c ->
            val (d, id) = dateIdCursor(c)
            DSL.row(EXPENSES.SPENT_DATE, EXPENSES.ID).lt(d, id)
        } ?: DSL.noCondition()
        val rows = dsl.selectFrom(EXPENSES)
            .where(visibility(m))
            .and(if (from != null) EXPENSES.SPENT_DATE.ge(from) else DSL.noCondition())
            .and(if (to != null) EXPENSES.SPENT_DATE.le(to) else DSL.noCondition())
            .and(if (membershipId != null) EXPENSES.MEMBERSHIP_ID.eq(membershipId) else DSL.noCondition())
            .and(if (projectId != null) EXPENSES.PROJECT_ID.eq(projectId) else DSL.noCondition())
            .and(seek)
            .orderBy(EXPENSES.SPENT_DATE.desc(), EXPENSES.ID.desc()).limit(n + 1).fetch()
        return Page.of(views(rows), n) { "${it.spentDate}_${it.id}" }
    }

    @Transactional(readOnly = true)
    fun get(m: Member, id: UUID) = views(listOf(loadVisible(m, id))).single()

    @Transactional
    fun create(m: Member, input: ExpenseInput): ExpenseView {
        m.requireWritable()
        val settings = accounts.get(m.accountId)
        val owner = input.membershipId ?: m.membershipId
        val projectId = input.projectId ?: throw ValidationException("project_id", "Choose a project")
        if (!access.canActOnTimeOf(m, owner, projectId)) throw ForbiddenException("You can only add expenses for people on projects you manage")
        requireAssigned(owner, projectId)
        val date = input.spentDate ?: settings.today(clock)
        requireWeekOpen(m, owner, date)
        val currency = dsl.select(CLIENTS.CURRENCY).from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID)).where(PROJECTS.ID.eq(projectId)).fetchOne()!!.value1()
        val r = dsl.newRecord(EXPENSES).apply {
            accountId = m.accountId
            membershipId = owner
            this.projectId = projectId
            spentDate = date
            this.currency = currency
        }
        applyInput(r, Patch.all(input, setOf("notes")), creating = true)
        r.store()
        budgets.touch(projectId)
        return views(listOf(r)).single()
    }

    @Transactional
    fun update(m: Member, id: UUID, patch: Patch<ExpenseInput>): ExpenseView {
        m.requireWritable()
        val r = loadVisible(m, id)
        ETags.checkIfMatch(r.updatedAt)
        requireEditable(m, r)
        val oldProject = r.projectId
        patch.value.projectId?.let {
            if (it != r.projectId) {
                if (!access.canActOnTimeOf(m, r.membershipId, it)) throw ForbiddenException("You don't manage that project")
                requireAssigned(r.membershipId, it)
                r.projectId = it
                r.currency = dsl.select(CLIENTS.CURRENCY).from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID)).where(PROJECTS.ID.eq(it)).fetchOne()!!.value1()
            }
        }
        patch.value.spentDate?.let {
            requireWeekOpen(m, r.membershipId, it)
            r.spentDate = it
        }
        applyInput(r, patch, creating = false)
        r.store()
        budgets.touch(r.projectId)
        if (oldProject != r.projectId) budgets.touch(oldProject)
        return views(listOf(r)).single()
    }

    @Transactional
    fun delete(m: Member, id: UUID) {
        m.requireWritable()
        val r = loadVisible(m, id)
        ETags.checkIfMatch(r.updatedAt)
        requireEditable(m, r)
        val receipt = r.receiptFileId
        r.delete()
        receipt?.let { files.delete(it) }
        budgets.touch(r.projectId)
    }

    @Transactional
    fun attachReceipt(m: Member, id: UUID, file: MultipartFile): ExpenseView {
        m.requireWritable()
        val r = loadVisible(m, id)
        requireEditable(m, r)
        val stored = files.store(m.accountId, file.originalFilename ?: "receipt", file.contentType ?: "application/octet-stream", file.bytes, m.membershipId, FileService.RECEIPT_TYPES)
        val previous = r.receiptFileId
        r.receiptFileId = stored.id
        r.store()
        previous?.let { files.delete(it) }
        return views(listOf(r)).single()
    }

    @Transactional
    fun removeReceipt(m: Member, id: UUID): ExpenseView {
        m.requireWritable()
        val r = loadVisible(m, id)
        requireEditable(m, r)
        val previous = r.receiptFileId
        r.receiptFileId = null
        r.store()
        previous?.let { files.delete(it) }
        return views(listOf(r)).single()
    }

    @Transactional(readOnly = true)
    fun receipt(m: Member, id: UUID): ResponseEntity<ByteArray> {
        val r = loadVisible(m, id)
        val f = files.load(r.receiptFileId ?: throw NotFoundException("Receipt"))
        // Images and PDFs are shown; anything else (older uploads, imports) is only downloaded,
        // and nothing served here may run script on our origin.
        val inline = f.record.mime in FileTypes.INLINE
        val disposition = if (inline) ContentDisposition.inline() else ContentDisposition.attachment()
        return ResponseEntity.ok()
            .contentType(if (inline) MediaType.parseMediaType(f.record.mime) else MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(f.record.filename).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", if (f.record.mime == "application/pdf") "default-src 'none'; frame-ancestors 'self'" else "sandbox; default-src 'none'")
            .body(f.bytes)
    }

    fun visibility(m: Member): Condition {
        val managed = access.managedProjectIds(m) ?: return DSL.noCondition()
        return EXPENSES.MEMBERSHIP_ID.eq(m.membershipId).or(EXPENSES.PROJECT_ID.`in`(managed))
    }

    private fun applyInput(r: ExpensesRecord, p: Patch<ExpenseInput>, creating: Boolean) {
        val i = p.value
        val categoryId = i.categoryId ?: r.categoryId ?: throw ValidationException("category_id", "Choose a category")
        val category = dsl.selectFrom(EXPENSE_CATEGORIES).where(EXPENSE_CATEGORIES.ID.eq(categoryId)).fetchOne()
            ?: throw ValidationException("category_id", "Unknown category")
        if (creating && !category.isActive) throw ValidationException("category_id", "This category is archived")
        r.categoryId = category.id
        if (category.unitPrice != null) {
            val units = i.units ?: r.units ?: throw ValidationException("units", "Enter the number of ${category.unitName}")
            if (units < BigDecimal.ZERO) throw ValidationException("units", "Must not be negative")
            r.units = units.setScale(2, java.math.RoundingMode.HALF_UP)
            r.amountMinor = Money.times(r.units, category.unitPrice)
        } else {
            val amount = i.amount ?: r.amountMinor ?: throw ValidationException("amount", "Enter the amount")
            if (amount < 0) throw ValidationException("amount", "Must not be negative")
            r.amountMinor = amount
            r.units = null
        }
        p.field("notes", { notes }) { r.notes = it?.trim()?.ifBlank { null } }
        val projectBillable = dsl.select(PROJECTS.IS_BILLABLE).from(PROJECTS).where(PROJECTS.ID.eq(r.projectId)).fetchOne()!!.value1()
        if (i.billable != null) r.billable = i.billable && projectBillable else if (creating) r.billable = projectBillable
    }

    private fun requireAssigned(owner: UUID, projectId: UUID) {
        val ok = dsl.fetchExists(
            PROJECT_MEMBERS.join(PROJECTS).on(PROJECTS.ID.eq(PROJECT_MEMBERS.PROJECT_ID)),
            PROJECT_MEMBERS.PROJECT_ID.eq(projectId).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(owner)).and(PROJECT_MEMBERS.IS_ACTIVE.isTrue).and(PROJECTS.IS_ACTIVE.isTrue),
        )
        if (!ok) throw ValidationException("project_id", "This person is not assigned to an active project with that id")
    }

    private fun requireWeekOpen(m: Member, owner: UUID, date: LocalDate) {
        val settings = accounts.get(m.accountId)
        if (!settings.approvalsEnabled) return
        val state = dsl.select(TIMESHEET_SUBMISSIONS.STATE).from(TIMESHEET_SUBMISSIONS)
            .where(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID.eq(owner)).and(TIMESHEET_SUBMISSIONS.WEEK_START_DATE.eq(settings.weekStartOf(date))).fetchOne()?.value1()
        if (state == "approved") throw ConflictException("week_approved", "This week has been approved and is locked")
        if (state == "submitted" && owner == m.membershipId && !m.isAdmin) throw ConflictException("week_submitted", "This week has been submitted for approval")
    }

    private fun requireEditable(m: Member, r: ExpensesRecord) {
        if (r.invoiceId != null) throw ConflictException("invoiced", "This expense has been invoiced and is locked")
        if (r.isLocked || r.approvalState == "approved") throw ConflictException("entry_locked", "This expense is locked")
        if (r.approvalState == "submitted" && r.membershipId == m.membershipId && !m.isAdmin) throw ConflictException("week_submitted", "This expense has been submitted for approval")
    }

    private fun loadVisible(m: Member, id: UUID): ExpensesRecord =
        dsl.selectFrom(EXPENSES).where(EXPENSES.ID.eq(id)).and(visibility(m)).fetchOne() ?: throw NotFoundException("Expense")

    private fun views(rows: List<ExpensesRecord>): List<ExpenseView> {
        if (rows.isEmpty()) return emptyList()
        val people = dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.`in`(rows.map { it.membershipId })).fetchMap(MEMBERSHIPS.ID, MEMBERSHIPS.NAME)
        val projects = dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.CODE, CLIENTS.ID, CLIENTS.NAME).from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .where(PROJECTS.ID.`in`(rows.map { it.projectId })).fetchMap(PROJECTS.ID)
        val categories = dsl.select(EXPENSE_CATEGORIES.ID, EXPENSE_CATEGORIES.NAME).from(EXPENSE_CATEGORIES)
            .where(EXPENSE_CATEGORIES.ID.`in`(rows.map { it.categoryId })).fetchMap(EXPENSE_CATEGORIES.ID, EXPENSE_CATEGORIES.NAME)
        val receiptNames = dsl.select(com.honestrobin.time.db.Tables.FILES.ID, com.honestrobin.time.db.Tables.FILES.FILENAME).from(com.honestrobin.time.db.Tables.FILES)
            .where(com.honestrobin.time.db.Tables.FILES.ID.`in`(rows.mapNotNull { it.receiptFileId })).fetchMap(com.honestrobin.time.db.Tables.FILES.ID, com.honestrobin.time.db.Tables.FILES.FILENAME)
        return rows.map { r ->
            val p = projects.getValue(r.projectId)
            ExpenseView(
                id = r.id, spentDate = r.spentDate, person = Ref(r.membershipId, people[r.membershipId] ?: "?"),
                client = Ref(p[CLIENTS.ID], p[CLIENTS.NAME]), project = ProjectRef(r.projectId, p[PROJECTS.NAME], p[PROJECTS.CODE]),
                category = Ref(r.categoryId, categories[r.categoryId] ?: "?"), amount = r.amountMinor, currency = r.currency, units = r.units,
                notes = r.notes, billable = r.billable, hasReceipt = r.receiptFileId != null, receiptFilename = r.receiptFileId?.let { receiptNames[it] },
                approvalState = r.approvalState, isLocked = r.isLocked || r.invoiceId != null, invoiceId = r.invoiceId,
                createdAt = r.createdAt, updatedAt = r.updatedAt,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/expense_categories")
@Tag(name = "expenses")
class ExpenseCategoryController(private val categories: ExpenseCategoryService, private val patches: Patches) {
    @GetMapping
    fun list(@RequestParam(name = "is_active", required = false) isActive: Boolean?): List<ExpenseCategoryView> {
        Current.member()
        return categories.list(isActive)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: ExpenseCategoryInput) = categories.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(schema = Schema(implementation = ExpenseCategoryInput::class))]) @RequestBody body: JsonNode,
    ) = categories.update(Current.member(), id, patches.parse(body))
}

@RestController
@RequestMapping("/api/v1/expenses")
@Tag(name = "expenses")
class ExpenseController(private val expenses: ExpenseService, private val patches: Patches) {
    @GetMapping
    fun list(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "membership_id", required = false) membershipId: UUID?,
        @RequestParam(name = "project_id", required = false) projectId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = expenses.list(Current.member(), from, to, membershipId, projectId, cursor, limit)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = expenses.get(Current.member(), id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: ExpenseInput) = expenses.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(schema = Schema(implementation = ExpenseInput::class))]) @RequestBody body: JsonNode,
    ) = expenses.update(Current.member(), id, patches.parse(body))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = expenses.delete(Current.member(), id)

    @PostMapping("/{id}/receipt", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun attachReceipt(@PathVariable id: UUID, @RequestPart("file") file: MultipartFile) = expenses.attachReceipt(Current.member(), id, file)

    @DeleteMapping("/{id}/receipt")
    fun removeReceipt(@PathVariable id: UUID) = expenses.removeReceipt(Current.member(), id)

    @GetMapping("/{id}/receipt")
    fun receipt(@PathVariable id: UUID) = expenses.receipt(Current.member(), id)
}
