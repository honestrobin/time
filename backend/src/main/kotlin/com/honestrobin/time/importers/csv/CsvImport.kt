// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.csv

import com.fasterxml.jackson.databind.ObjectMapper
import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.FILES
import com.honestrobin.time.db.Tables.IMPORT_FILES
import com.honestrobin.time.db.Tables.IMPORT_ISSUES
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.files.FileService
import com.honestrobin.time.importers.harvest.Links
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.BadRequestException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class CsvMappingInput(
    val kind: CsvKind? = null,
    /** Field name → column header; a missing or null field is not in the file. */
    val mapping: Map<String, String?>? = null,
    val dateOrder: CsvValues.DateOrder? = null,
    /** Email addresses for people in the file who aren't in the account yet, by their name in the file. */
    val people: Map<String, String>? = null,
)

data class CsvFieldView(val name: String, val label: String, val required: Boolean)
data class CsvKindOption(val kind: CsvKind, val label: String)
data class CsvPerson(val name: String, val email: String?, val existing: Boolean, val entries: Int)
data class CsvProblem(val row: Int, val message: String)
data class CsvCreates(val clients: List<String>, val projects: List<String>, val tasks: List<String>, val people: List<CsvPerson>)

data class CsvPreview(
    val jobId: UUID,
    val fileName: String,
    val kind: CsvKind,
    val kinds: List<CsvKindOption>,
    val columns: List<String>,
    val fields: List<CsvFieldView>,
    val mapping: Map<String, String?>,
    val dateOrder: CsvValues.DateOrder?,
    /** Every date fits both day-first and month-first: the person must say which. */
    val dateOrderNeeded: Boolean,
    val rows: Int,
    val valid: Int,
    /** The first rows as they will be read. */
    val sample: List<Map<String, String?>>,
    val problems: List<CsvProblem>,
    val problemCount: Int,
    val totalHours: BigDecimal?,
    val creates: CsvCreates,
)

data class CsvResult(val jobId: UUID, val status: String, val stats: Map<String, Int>, val skipped: Int)

/** One row of a time report, read. */
private data class TimeRow(
    val row: Int,
    val date: LocalDate,
    val client: String,
    val project: String,
    val projectCode: String?,
    val task: String,
    val notes: String?,
    val seconds: Long,
    val person: String,
    val email: String?,
    val billable: Boolean,
    val billableRate: BigDecimal?,
    val costRate: BigDecimal?,
    val currency: String?,
    val approved: Boolean,
    val invoiced: Boolean,
    val url: String?,
) {
    val personKey get() = email?.lowercase() ?: person.lowercase()
}

/**
 * Importing Harvest's own CSV exports (spec §6.6), for when the API can't be used, offline too:
 * the detailed time report, and the clients, contacts and projects exports. Columns are matched by
 * their headers (any language of synonyms the person adjusts in the mapping), every row is
 * checked in a preview before anything is written, and re-importing a file updates in place:
 * each time row is keyed by a hash of what it says, plus how often the same row came before.
 */
@Service
class CsvImportService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val files: FileService,
    private val json: ObjectMapper,
    private val funnel: Funnel,
) {
    fun upload(m: Member, filename: String, bytes: ByteArray): CsvPreview {
        m.requireWritable()
        m.requireAdmin()
        val table = read(bytes)
        val kind = CsvFields.detect(table.columns)
        val jobId = tx.run {
            if (dsl.fetchExists(IMPORT_JOBS, IMPORT_JOBS.STATUS.`in`("queued", "running"))) {
                throw ConflictException("import_running", "An import is already running for this account")
            }
            val job = dsl.insertInto(IMPORT_JOBS)
                .set(IMPORT_JOBS.ACCOUNT_ID, m.accountId).set(IMPORT_JOBS.SOURCE, "harvest").set(IMPORT_JOBS.MODE, "csv").set(IMPORT_JOBS.STATUS, "preview")
                .set(IMPORT_JOBS.STARTED_BY, m.membershipId)
                .returning(IMPORT_JOBS.ID).fetchOne()!!.id
            val file = files.store(m.accountId, filename, "text/csv", bytes, m.membershipId)
            dsl.insertInto(IMPORT_FILES).set(IMPORT_FILES.ACCOUNT_ID, m.accountId).set(IMPORT_FILES.JOB_ID, job).set(IMPORT_FILES.FILE_ID, file.id)
                .set(IMPORT_FILES.KIND, kind.name).set(IMPORT_FILES.MAPPING, JSONB.valueOf(json.writeValueAsString(mapOf("fields" to CsvFields.propose(kind, table.columns)))))
                .execute()
            job
        }
        return preview(m, jobId, null)
    }

    /** The preview, with [input] (kind, mapping, date order, emails) applied and remembered. */
    fun preview(m: Member, jobId: UUID, input: CsvMappingInput?): CsvPreview {
        m.requireAdmin()
        val (state, table, fileName) = tx.run { load(jobId, input) }
        return tx.run { analyse(m.accountId, jobId, fileName, table, state) }
    }

    fun commit(m: Member, jobId: UUID, input: CsvMappingInput?): CsvResult {
        m.requireWritable()
        m.requireAdmin()
        val (state, table, _) = tx.run { load(jobId, input) }
        val account = m.accountId
        tx.run { dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "running").set(IMPORT_JOBS.STARTED_AT, Instant.now()).where(IMPORT_JOBS.ID.eq(jobId)).execute() }
        funnel.event(account, Funnel.IMPORT_STARTED, mapOf("source" to "harvest_csv"))
        try {
            val (stats, problems) = when (state.kind) {
                CsvKind.TIME -> commitTime(account, jobId, table, state)
                CsvKind.CLIENTS -> commitClients(account, table, state)
                CsvKind.CONTACTS -> commitContacts(account, table, state)
                CsvKind.PROJECTS -> commitProjects(account, table, state)
            }
            tx.run {
                problems.forEach { p ->
                    dsl.insertInto(IMPORT_ISSUES).set(IMPORT_ISSUES.ACCOUNT_ID, account).set(IMPORT_ISSUES.JOB_ID, jobId).set(IMPORT_ISSUES.SEVERITY, "error")
                        .set(IMPORT_ISSUES.ENTITY_TYPE, "csv_row").set(IMPORT_ISSUES.EXTERNAL_ID, p.row.toString()).set(IMPORT_ISSUES.REASON, p.message).execute()
                }
                dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "completed").set(IMPORT_JOBS.PHASE, "done").set(IMPORT_JOBS.FINISHED_AT, Instant.now())
                    .set(IMPORT_JOBS.STATS, JSONB.valueOf(json.writeValueAsString(stats + ("skipped" to problems.size) + ("kind" to state.kind.name))))
                    .where(IMPORT_JOBS.ID.eq(jobId)).execute()
            }
            return CsvResult(jobId, "completed", stats, problems.size)
        } catch (e: Exception) {
            tx.run {
                dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "preview").set(IMPORT_JOBS.ERROR, "The import stopped: ${e.message}. Nothing after the last saved part was written; import the file again to finish.")
                    .where(IMPORT_JOBS.ID.eq(jobId)).execute()
            }
            throw e
        }
    }

    // ------------------------------------------------------------------ reading

    private data class State(val kind: CsvKind, val mapping: Map<String, String?>, val dateOrder: CsvValues.DateOrder?, val people: Map<String, String>)

    private fun read(bytes: ByteArray): CsvTable = try {
        CsvTable.read(bytes)
    } catch (e: CsvException) {
        throw BadRequestException("csv_unreadable", e.message ?: "This file can't be read as CSV")
    } catch (e: Exception) {
        throw BadRequestException("csv_unreadable", "This file can't be read as CSV: ${e.message}")
    }

    private fun load(jobId: UUID, input: CsvMappingInput?): Triple<State, CsvTable, String> {
        val job = dsl.selectFrom(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(jobId)).and(IMPORT_JOBS.MODE.eq("csv")).fetchOne() ?: throw NotFoundException("Import")
        if (job.status != "preview") throw ConflictException("not_in_preview", "This file was imported already. Upload it again to import it again.")
        val f = dsl.selectFrom(IMPORT_FILES).where(IMPORT_FILES.JOB_ID.eq(jobId)).fetchOne() ?: throw NotFoundException("Import file")
        val stored = dsl.selectFrom(FILES).where(FILES.ID.eq(f.fileId)).fetchOne()!!
        val table = read(files.load(f.fileId).bytes)
        val saved = json.readTree(f.mapping.data())
        var kind = runCatching { CsvKind.valueOf(f.kind) }.getOrDefault(CsvFields.detect(table.columns))
        var mapping: Map<String, String?> = saved["fields"]?.fields()?.asSequence()?.associate { it.key to it.value.takeIf { v -> !v.isNull }?.asText() } ?: emptyMap()
        var dateOrder = saved["date_order"]?.takeIf { !it.isNull }?.asText()?.let { runCatching { CsvValues.DateOrder.valueOf(it) }.getOrNull() }
        var people: Map<String, String> = saved["people"]?.fields()?.asSequence()?.associate { it.key to it.value.asText() } ?: emptyMap()
        if (input != null) {
            if (input.kind != null && input.kind != kind) {
                kind = input.kind
                mapping = CsvFields.propose(kind, table.columns)
            }
            input.mapping?.let { m -> mapping = CsvFields.fields.getValue(kind).associate { it.name to m[it.name]?.takeIf { c -> c in table.columns } } }
            input.dateOrder?.let { dateOrder = it }
            input.people?.let { people = it.mapValues { (_, v) -> v.trim() }.filterValues { it.isNotEmpty() } }
            dsl.update(IMPORT_FILES).set(IMPORT_FILES.KIND, kind.name)
                .set(IMPORT_FILES.MAPPING, JSONB.valueOf(json.writeValueAsString(mapOf("fields" to mapping, "date_order" to dateOrder?.name, "people" to people))))
                .where(IMPORT_FILES.ID.eq(f.id)).execute()
        }
        return Triple(State(kind, mapping, dateOrder, people), table, stored.filename)
    }

    private fun Map<String, String?>.col(field: String) = this[field]

    private fun interpretTime(table: CsvTable, state: State): Triple<List<TimeRow>, List<CsvProblem>, CsvValues.DateOrder?> {
        val m = state.mapping
        val order = state.dateOrder ?: CsvValues.dateOrder(table.rows.asSequence().mapNotNull { table.value(it, m.col("date")) })
        val rows = ArrayList<TimeRow>()
        val problems = ArrayList<CsvProblem>()
        table.rows.forEachIndexed { i, r ->
            val n = i + 2 // as a spreadsheet numbers it: the header is row 1
            fun v(field: String) = table.value(r, m.col(field))
            val rawDate = v("date") ?: return@forEachIndexed run { problems += CsvProblem(n, "No date") }
            // Undecided order: every value fits both, so read day-first for the preview; committing waits for a choice.
            val date = CsvValues.date(rawDate, order ?: CsvValues.DateOrder.DMY) ?: return@forEachIndexed run { problems += CsvProblem(n, "\"$rawDate\" isn't a date") }
            val client = v("client") ?: return@forEachIndexed run { problems += CsvProblem(n, "No client") }
            val project = v("project") ?: return@forEachIndexed run { problems += CsvProblem(n, "No project") }
            val task = v("task") ?: return@forEachIndexed run { problems += CsvProblem(n, "No task") }
            val rawHours = v("hours") ?: return@forEachIndexed run { problems += CsvProblem(n, "No hours") }
            val seconds = CsvValues.hours(rawHours) ?: return@forEachIndexed run { problems += CsvProblem(n, "\"$rawHours\" isn't a number of hours") }
            if (seconds > 86_400) return@forEachIndexed run { problems += CsvProblem(n, "More than 24 hours in one entry") }
            val person = v("person") ?: listOfNotNull(v("first_name"), v("last_name")).joinToString(" ").ifBlank { null } ?: v("email")
                ?: return@forEachIndexed run { problems += CsvProblem(n, "No person") }
            val rawCurrency = v("currency")
            val currency = rawCurrency?.let(CsvValues::currency)
            if (rawCurrency != null && currency == null) return@forEachIndexed run { problems += CsvProblem(n, "\"$rawCurrency\" isn't a currency") }
            rows += TimeRow(
                row = n, date = date, client = client.take(200), project = project.take(200), projectCode = v("project_code")?.take(50), task = task.take(200),
                notes = v("notes"), seconds = seconds, person = person.take(200), email = v("email")?.lowercase()?.takeIf { it.contains('@') },
                billable = m.col("billable")?.let { CsvValues.yes(v("billable")) } ?: (v("billable_rate")?.let(CsvValues::number)?.signum() == 1),
                billableRate = v("billable_rate")?.let(CsvValues::number), costRate = v("cost_rate")?.let(CsvValues::number), currency = currency,
                approved = CsvValues.yes(v("approved")), invoiced = CsvValues.yes(v("invoiced")),
                url = v("external_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(2000),
            )
        }
        return Triple(rows, problems, order)
    }

    // ------------------------------------------------------------------ preview

    private fun analyse(account: UUID, jobId: UUID, fileName: String, table: CsvTable, state: State): CsvPreview {
        val fields = CsvFields.fields.getValue(state.kind)
        val missing = fields.filter { it.required && state.mapping[it.name] == null }
        var problems = missing.map { CsvProblem(1, "Choose the column for \"${it.label}\"") }
        var valid = 0
        var hours: BigDecimal? = null
        var order: CsvValues.DateOrder? = state.dateOrder
        var orderNeeded = false
        val sample = ArrayList<Map<String, String?>>()
        var creates = CsvCreates(emptyList(), emptyList(), emptyList(), emptyList())
        if (missing.isEmpty()) {
            when (state.kind) {
                CsvKind.TIME -> {
                    val (rows, p, o) = interpretTime(table, state)
                    order = o
                    orderNeeded = o == null && state.mapping["date"] != null
                    problems = p
                    valid = rows.size
                    hours = BigDecimal(rows.sumOf { it.seconds }).divide(BigDecimal(3600), 2, RoundingMode.HALF_UP)
                    rows.take(10).forEach { r ->
                        sample += linkedMapOf("date" to r.date.toString(), "person" to r.person, "client" to r.client, "project" to r.project, "task" to r.task,
                            "hours" to BigDecimal(r.seconds).divide(BigDecimal(3600), 2, RoundingMode.HALF_UP).toPlainString(), "notes" to r.notes,
                            "billable" to if (r.billable) "yes" else "no")
                    }
                    creates = timeCreates(rows, state)
                }
                else -> {
                    val (rows, p) = interpretSimple(table, state)
                    problems = p
                    valid = rows.size
                    rows.take(10).forEach { sample += it }
                    creates = simpleCreates(state.kind, rows)
                }
            }
        }
        return CsvPreview(
            jobId = jobId, fileName = fileName, kind = state.kind, kinds = CsvKind.entries.map { CsvKindOption(it, it.label) }, columns = table.columns,
            fields = fields.map { CsvFieldView(it.name, it.label, it.required) }, mapping = fields.associate { it.name to state.mapping[it.name] },
            dateOrder = order, dateOrderNeeded = orderNeeded, rows = table.rows.size, valid = valid, sample = sample,
            problems = problems.take(50), problemCount = problems.size, totalHours = hours, creates = creates,
        )
    }

    private fun lower(f: org.jooq.Field<String>) = DSL.lower(f)

    private fun timeCreates(rows: List<TimeRow>, state: State): CsvCreates {
        val clients = rows.map { it.client }.distinctBy { it.lowercase() }
        val existingClients = dsl.select(CLIENTS.NAME).from(CLIENTS).fetch(CLIENTS.NAME).map { it.lowercase() }.toSet()
        val existingProjects = dsl.select(CLIENTS.NAME, PROJECTS.NAME).from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .fetch().map { it.value1().lowercase() to it.value2().lowercase() }.toSet()
        val existingTasks = dsl.select(TASKS.NAME).from(TASKS).fetch(TASKS.NAME).map { it.lowercase() }.toSet()
        val byPerson = rows.groupBy { it.personKey }
        val people = byPerson.values.map { list ->
            val first = list.first()
            val existing = findMembership(first.email, first.person) != null
            CsvPerson(first.person, first.email ?: state.people[first.person], existing, list.size)
        }.sortedWith(compareBy({ it.existing }, { it.name }))
        return CsvCreates(
            clients = clients.filter { it.lowercase() !in existingClients },
            projects = rows.map { it.client to it.project }.distinctBy { it.first.lowercase() to it.second.lowercase() }
                .filter { (c, p) -> (c.lowercase() to p.lowercase()) !in existingProjects }.map { "${it.first} › ${it.second}" },
            tasks = rows.map { it.task }.distinctBy { it.lowercase() }.filter { it.lowercase() !in existingTasks },
            people = people,
        )
    }

    private fun findMembership(email: String?, name: String): UUID? =
        email?.let { dsl.select(MEMBERSHIPS.ID).from(MEMBERSHIPS).where(lower(MEMBERSHIPS.EMAIL).eq(it.lowercase())).fetchOne()?.value1() }
            ?: dsl.select(MEMBERSHIPS.ID).from(MEMBERSHIPS).where(lower(MEMBERSHIPS.NAME).eq(name.lowercase())).limit(1).fetchOne()?.value1()

    /** Clients, contacts and projects: one row each, as field → value. */
    private fun interpretSimple(table: CsvTable, state: State): Pair<List<Map<String, String?>>, List<CsvProblem>> {
        val fields = CsvFields.fields.getValue(state.kind)
        val rows = ArrayList<Map<String, String?>>()
        val problems = ArrayList<CsvProblem>()
        table.rows.forEachIndexed { i, r ->
            val values = fields.associate { it.name to table.value(r, state.mapping[it.name]) }
            val missing = fields.firstOrNull { it.required && values[it.name] == null }
            if (missing != null) problems += CsvProblem(i + 2, "No ${missing.label.lowercase()}") else rows += values
        }
        return rows to problems
    }

    private fun simpleCreates(kind: CsvKind, rows: List<Map<String, String?>>): CsvCreates {
        val existingClients = dsl.select(CLIENTS.NAME).from(CLIENTS).fetch(CLIENTS.NAME).map { it.lowercase() }.toSet()
        val clientNames = when (kind) {
            CsvKind.CLIENTS -> rows.mapNotNull { it["name"] }
            else -> rows.mapNotNull { it["client"] }
        }.distinctBy { it.lowercase() }.filter { it.lowercase() !in existingClients }
        val projects = if (kind == CsvKind.PROJECTS) {
            val existing = dsl.select(CLIENTS.NAME, PROJECTS.NAME).from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
                .fetch().map { it.value1().lowercase() to it.value2().lowercase() }.toSet()
            rows.map { it["client"]!! to it["project"]!! }.distinctBy { it.first.lowercase() to it.second.lowercase() }
                .filter { (c, p) -> (c.lowercase() to p.lowercase()) !in existing }.map { "${it.first} › ${it.second}" }
        } else {
            emptyList()
        }
        return CsvCreates(clientNames, projects, emptyList(), emptyList())
    }

    // ------------------------------------------------------------------ writing

    private class Catalog(val account: UUID, val accountCurrency: String) {
        val clients = HashMap<String, UUID>()
        val clientCurrency = HashMap<UUID, String>()
        val projects = HashMap<Pair<UUID, String>, UUID>()
        val tasks = HashMap<String, UUID>()
        val people = HashMap<String, UUID>()
        var created = mutableMapOf("clients" to 0, "projects" to 0, "tasks" to 0, "people" to 0)
    }

    private fun accountCurrency(account: UUID) = dsl.select(ACCOUNTS.DEFAULT_CURRENCY).from(ACCOUNTS).where(ACCOUNTS.ID.eq(account)).fetchOne()!!.value1()

    private fun client(c: Catalog, name: String, currency: String?): UUID = c.clients.getOrPut(name.lowercase()) {
        val found = dsl.selectFrom(CLIENTS).where(lower(CLIENTS.NAME).eq(name.lowercase())).limit(1).fetchOne()
        val r = found ?: dsl.newRecord(CLIENTS).apply {
            accountId = c.account
            this.name = name.trim().take(200)
            this.currency = currency ?: c.accountCurrency
            store()
            c.created.merge("clients", 1, Int::plus)
        }
        c.clientCurrency[r.id] = r.currency
        r.id
    }

    private fun project(c: Catalog, clientId: UUID, name: String, init: (com.honestrobin.time.db.tables.records.ProjectsRecord) -> Unit): UUID =
        c.projects.getOrPut(clientId to name.lowercase()) {
            dsl.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.CLIENT_ID.eq(clientId)).and(lower(PROJECTS.NAME).eq(name.lowercase())).limit(1).fetchOne()?.value1()
                ?: dsl.newRecord(PROJECTS).apply {
                    accountId = c.account
                    this.clientId = clientId
                    this.name = name.trim().take(200)
                    init(this)
                    store()
                    c.created.merge("projects", 1, Int::plus)
                }.id
        }

    private fun task(c: Catalog, name: String, billable: Boolean): UUID = c.tasks.getOrPut(name.lowercase()) {
        dsl.select(TASKS.ID).from(TASKS).where(lower(TASKS.NAME).eq(name.lowercase())).limit(1).fetchOne()?.value1()
            ?: dsl.newRecord(TASKS).apply {
                accountId = c.account
                this.name = name.trim().take(200)
                defaultBillable = billable
                store()
                c.created.merge("tasks", 1, Int::plus)
            }.id
    }

    /** A person in the file: matched by email, then name; otherwise added, not invited, with the email given (or a placeholder to fill in). */
    private fun person(c: Catalog, row: TimeRow, emails: Map<String, String>): UUID = c.people.getOrPut(row.personKey) {
        val email = row.email ?: emails[row.person]?.lowercase()?.takeIf { it.contains('@') }
        findMembership(email, row.person) ?: dsl.newRecord(MEMBERSHIPS).apply {
            accountId = c.account
            name = row.person
            this.email = email ?: placeholderEmail(row.person)
            role = "member"
            status = "pending_invite"
            store()
            c.created.merge("people", 1, Int::plus)
        }.id
    }

    private fun placeholderEmail(name: String): String {
        val slug = name.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '.' }.joinToString("").replace(Regex("\\.+"), ".").trim('.').ifBlank { "person" }
        var candidate = "$slug@import.invalid"
        var n = 2
        while (dsl.fetchExists(MEMBERSHIPS, lower(MEMBERSHIPS.EMAIL).eq(candidate))) candidate = "$slug.${n++}@import.invalid"
        return candidate
    }

    private fun commitTime(account: UUID, jobId: UUID, table: CsvTable, state: State): Pair<Map<String, Int>, List<CsvProblem>> {
        val (rows, problems, order) = tx.run { interpretTime(table, state) }
        if (order == null && table.rows.any { r -> table.value(r, state.mapping["date"])?.let { !it.matches(Regex("^\\d{4}-.*")) } == true }) {
            throw BadRequestException("date_order_needed", "Say whether the dates are day-first or month-first")
        }
        val catalog = tx.run { Catalog(account, accountCurrency(account)) }
        // The catalogue first, in one go: clients, projects with their tasks and people.
        tx.run {
            val byProject = rows.groupBy { it.client.lowercase() to it.project.lowercase() }
            byProject.values.forEach { list ->
                val first = list.first()
                val clientId = client(catalog, first.client, first.currency)
                val currency = catalog.clientCurrency.getValue(clientId)
                val billable = list.any { it.billable }
                val projectId = project(catalog, clientId, first.project) { p ->
                    p.code = first.projectCode
                    p.isBillable = billable
                    p.billBy = "project"
                    // The rate most entries were billed at, for time tracked from now on.
                    p.hourlyRate = list.mapNotNull { r -> r.billableRate?.takeIf { r.billable } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { Money.toMinor(it, currency) }
                }
                list.groupBy { it.task.lowercase() }.values.forEach { t ->
                    val taskId = task(catalog, t.first().task, t.any { it.billable })
                    dsl.insertInto(PROJECT_TASKS).set(PROJECT_TASKS.ACCOUNT_ID, account).set(PROJECT_TASKS.PROJECT_ID, projectId).set(PROJECT_TASKS.TASK_ID, taskId)
                        .set(PROJECT_TASKS.BILLABLE, t.any { it.billable }).onConflictDoNothing().execute()
                }
                list.distinctBy { it.personKey }.forEach { r ->
                    dsl.insertInto(PROJECT_MEMBERS).set(PROJECT_MEMBERS.ACCOUNT_ID, account).set(PROJECT_MEMBERS.PROJECT_ID, projectId)
                        .set(PROJECT_MEMBERS.MEMBERSHIP_ID, person(catalog, r, state.people)).onConflictDoNothing().execute()
                }
            }
        }
        // Then the entries, in parts, each keyed so a second import of the file changes nothing.
        val occurrences = HashMap<String, Int>()
        val keyed = rows.map { r ->
            val base = Tokens.sha256Hex("${r.date}|${r.personKey}|${r.client.lowercase()}|${r.project.lowercase()}|${r.task.lowercase()}|${r.notes.orEmpty()}|${r.seconds}".toByteArray())
            val n = occurrences.merge(base, 1, Int::plus)!!
            "$base#$n" to r
        }
        var created = 0
        var updated = 0
        val weekStart = tx.run { DayOfWeek.of(dsl.select(ACCOUNTS.WEEK_START).from(ACCOUNTS).where(ACCOUNTS.ID.eq(account)).fetchOne()!!.value1().toInt()) }
        keyed.chunked(1000).forEach { part ->
            tx.run {
                val links = Links(dsl, account, SYSTEM)
                val existing = links.find("time_entry", part.map { it.first })
                val records = existing.values.takeIf { it.isNotEmpty() }?.let { ids -> dsl.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.ID.`in`(ids)).fetch().associateBy { it.id } }.orEmpty()
                val newLinks = ArrayList<Triple<String, UUID, String?>>()
                part.forEach { (key, r) ->
                    val clientId = client(catalog, r.client, r.currency)
                    val projectId = catalog.projects.getValue(clientId to r.project.lowercase())
                    val currency = catalog.clientCurrency.getValue(clientId)
                    val e = existing[key]?.let(records::get) ?: dsl.newRecord(TIME_ENTRIES).apply { accountId = account }
                    if (e.invoiceId != null || e.timerStartedAt != null) return@forEach // invoiced or timing here since: left alone
                    val isNew = e.id == null
                    e.membershipId = person(catalog, r, state.people)
                    e.projectId = projectId
                    e.taskId = catalog.tasks.getValue(r.task.lowercase())
                    e.spentDate = r.date
                    e.durationSeconds = r.seconds.toInt()
                    e.notes = r.notes
                    e.billable = r.billable
                    e.billableRateSnapshot = if (r.billable) r.billableRate?.let { Money.toMinor(it, r.currency ?: currency) } ?: 0 else 0
                    e.costRateSnapshot = r.costRate?.let { Money.toMinor(it, catalog.accountCurrency) } ?: 0
                    e.approvalState = if (r.approved) "approved" else "draft"
                    e.isLocked = r.approved || r.invoiced
                    e.lockedReason = if (r.invoiced) "Invoiced in Harvest" else if (r.approved) "Approved in Harvest" else null
                    r.url?.let { u ->
                        e.externalSource = "harvest"
                        e.externalId = u.takeLast(200)
                        e.externalUrl = u
                    }
                    e.store()
                    if (isNew) {
                        created++
                        newLinks += Triple(key, e.id, null)
                    } else {
                        updated++
                    }
                }
                links.putAll("time_entry", newLinks)
            }
        }
        // Weeks approved in Harvest are approved here too.
        tx.run {
            keyed.map { it.second }.filter { it.approved }.groupBy { catalog.people.getValue(it.personKey) to it.date.with(TemporalAdjusters.previousOrSame(weekStart)) }.keys.forEach { (membership, week) ->
                dsl.insertInto(TIMESHEET_SUBMISSIONS).set(TIMESHEET_SUBMISSIONS.ACCOUNT_ID, account).set(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID, membership)
                    .set(TIMESHEET_SUBMISSIONS.WEEK_START_DATE, week).set(TIMESHEET_SUBMISSIONS.STATE, "approved")
                    .onConflict(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID, TIMESHEET_SUBMISSIONS.WEEK_START_DATE).doUpdate().set(TIMESHEET_SUBMISSIONS.STATE, "approved")
                    .execute()
            }
        }
        return (catalog.created + mapOf("entries_created" to created, "entries_updated" to updated)) to problems
    }

    private fun commitClients(account: UUID, table: CsvTable, state: State): Pair<Map<String, Int>, List<CsvProblem>> = tx.run {
        val (rows, problems) = interpretSimple(table, state)
        val catalog = Catalog(account, accountCurrency(account))
        var updated = 0
        rows.forEach { row ->
            val currency = row["currency"]?.let(CsvValues::currency)
            val id = client(catalog, row["name"]!!, currency)
            val r = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(id)).fetchOne()!!
            row["address"]?.let { address ->
                val lines = address.lines().flatMap { it.split(",") }.map(String::trim).filter(String::isNotEmpty)
                r.addressLine1 = lines.firstOrNull()
                r.addressLine2 = lines.drop(1).joinToString(", ").ifBlank { null }
            }
            if (r.changed()) {
                r.store()
                updated++
            }
        }
        (catalog.created + ("clients_updated" to updated)) to problems
    }

    private fun commitContacts(account: UUID, table: CsvTable, state: State): Pair<Map<String, Int>, List<CsvProblem>> = tx.run {
        val (rows, problems) = interpretSimple(table, state)
        val catalog = Catalog(account, accountCurrency(account))
        var created = 0
        rows.forEach { row ->
            val clientId = client(catalog, row["client"]!!, null)
            val name = listOfNotNull(row["first_name"], row["last_name"]).joinToString(" ").ifBlank { row["email"] ?: "Contact" }.take(200)
            val existing = dsl.selectFrom(CLIENT_CONTACTS).where(CLIENT_CONTACTS.CLIENT_ID.eq(clientId))
                .and(row["email"]?.let { lower(CLIENT_CONTACTS.EMAIL).eq(it.lowercase()) } ?: lower(CLIENT_CONTACTS.NAME).eq(name.lowercase())).limit(1).fetchOne()
            val r = existing ?: dsl.newRecord(CLIENT_CONTACTS).apply { accountId = account; this.clientId = clientId }.also { created++ }
            r.name = name
            r.title = row["title"]
            r.email = row["email"]
            r.phone = row["phone"]
            r.store()
        }
        (catalog.created + ("contacts_created" to created)) to problems
    }

    private fun commitProjects(account: UUID, table: CsvTable, state: State): Pair<Map<String, Int>, List<CsvProblem>> = tx.run {
        val (rows, problems) = interpretSimple(table, state)
        val catalog = Catalog(account, accountCurrency(account))
        rows.forEach { row ->
            val clientId = client(catalog, row["client"]!!, null)
            val id = project(catalog, clientId, row["project"]!!) { p -> p.billBy = "project" }
            val r = dsl.selectFrom(PROJECTS).where(PROJECTS.ID.eq(id)).fetchOne()!!
            row["code"]?.let { r.code = it.take(50) }
            if (state.mapping["billable"] != null) r.isBillable = CsvValues.yes(row["billable"])
            row["notes"]?.let { r.notes = it }
            r.store()
        }
        catalog.created to problems
    }

    companion object {
        const val SYSTEM = "harvest-csv"
    }
}

@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "imports", description = "Importing from Harvest (admins)")
class CsvImportController(private val service: CsvImportService) {
    /** Upload one of Harvest's CSV exports; nothing is written until `commit`. */
    @PostMapping("/csv", consumes = ["multipart/form-data"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a Harvest CSV export and see how it will be read (spec §6.6)")
    fun upload(@RequestParam("file") file: MultipartFile): CsvPreview =
        service.upload(Current.member(), file.originalFilename ?: "import.csv", file.bytes)

    @PostMapping("/{id}/csv/preview")
    @Operation(summary = "Change the kind, column mapping, date order or emails, and see the result")
    fun preview(@PathVariable id: UUID, @RequestBody body: CsvMappingInput) = service.preview(Current.member(), id, body)

    @PostMapping("/{id}/csv/commit")
    @Operation(summary = "Import the rows that read without problems")
    fun commit(@PathVariable id: UUID, @RequestBody body: CsvMappingInput) = service.commit(Current.member(), id, body)
}
