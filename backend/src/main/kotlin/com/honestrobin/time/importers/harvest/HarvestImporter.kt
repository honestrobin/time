// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.harvest

import com.honestrobin.time.analytics.Funnel
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.ARCHIVED_DOCUMENTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.EXPENSE_CATEGORIES
import com.honestrobin.time.db.Tables.IMPORT_ISSUES
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.INVOICE_LINES
import com.honestrobin.time.db.Tables.INVOICE_SEQUENCES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PAYMENTS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TEAMS
import com.honestrobin.time.db.Tables.TEAM_MEMBERSHIPS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.tables.records.ImportJobsRecord
import com.honestrobin.time.files.FileService
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.crypto.SecretBox
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** Where a run stands. Saved with every page, in the same transaction as the page's rows. */
data class ImportProgress(
    val phase: String? = null,
    val step: String? = null,
    /** The next page of [step] to fetch; null means start the step from its first page. */
    val next: String? = null,
    val done: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
)

/**
 * Imports a Harvest account (spec §6) as one resumable run: structure, then recent work, then
 * history. Each page of Harvest data is written in its own transaction together with the
 * checkpoint, so a run that dies resumes at the page after the last one committed, and every row
 * is matched through external_links, so a re-run updates in place instead of duplicating.
 */
@Service
class HarvestImporter(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val settings: HarvestSettings,
    private val secrets: SecretBox,
    private val files: FileService,
    private val clientFactory: HarvestClientFactory,
    private val verifier: HarvestVerifier,
    private val json: ObjectMapper,
    private val funnel: Funnel,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One pass over Harvest: a (resumable) import, or with [updatedSince] a sync of what changed since then. */
    private inner class Run(val job: ImportJobsRecord, val client: HarvestClient, var progress: ImportProgress, val updatedSince: Instant? = null) {
        val syncing get() = updatedSince != null
        val accountId: UUID = job.accountId
        val links = Links(dsl, accountId)
        val today: LocalDate = LocalDate.now(clock)
        val recentFrom: LocalDate = today.minusDays(settings.recentDays)
        lateinit var accountCurrency: String
        lateinit var weekStart: DayOfWeek
        val projectCurrency = HashMap<UUID, String>()
        val options: JsonNode = json.readTree(job.options.data())
        val importReceipts get() = options["import_receipts"]?.asBoolean() ?: true
    }

    fun run(jobId: UUID) {
        val job = tx.system { dsl.selectFrom(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(jobId)).fetchOne() } ?: return
        if (job.status in setOf("completed", "cancelled", "syncing")) return
        DbContext.forAccount(job.accountId) {
            val token = job.tokenEncrypted?.let(secrets::decrypt)
            if (token == null) {
                fail(job.id, "The Harvest token is no longer stored. Start a new import.")
                return@forAccount
            }
            val run = Run(job, clientFactory.create(token, job.externalAccountId ?: ""), readProgress(job))
            tx.run {
                dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "running").set(IMPORT_JOBS.ERROR, null as String?)
                    .set(IMPORT_JOBS.STARTED_AT, DSL.coalesce(IMPORT_JOBS.STARTED_AT, Instant.now(clock)))
                    .where(IMPORT_JOBS.ID.eq(job.id)).execute()
                val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(job.accountId)).fetchOne()!!
                run.accountCurrency = account.defaultCurrency
                run.weekStart = DayOfWeek.of(account.weekStart.toInt())
            }
            try {
                steps.forEach { (name, phase, body) ->
                    if (name in run.progress.done) return@forEach
                    if (cancelled(job.id)) return@forAccount
                    log.info("Harvest import {}: {}", job.id, name)
                    body(run)
                    run.progress = run.progress.copy(phase = phase, step = null, next = null, done = run.progress.done + name)
                    tx.run { saveProgress(run) }
                }
                tx.run {
                    // With a sync window (spec §6, step 4), changes keep coming over until the cutover date,
                    // starting with those made while the import ran.
                    val syncUntil = job.syncUntil?.takeIf { it.isAfter(Instant.now()) }
                    val startedAt = dsl.select(IMPORT_JOBS.STARTED_AT).from(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(job.id)).fetchOne()?.value1()
                    dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, if (syncUntil != null) "syncing" else "completed").set(IMPORT_JOBS.PHASE, "done")
                        .set(IMPORT_JOBS.FINISHED_AT, Instant.now(clock))
                        .set(IMPORT_JOBS.STATS, JSONB.valueOf(json.writeValueAsString(run.progress.counts + ("requests" to run.client.requestCount))))
                        .set(IMPORT_JOBS.LAST_SYNCED_AT, if (syncUntil != null) startedAt ?: Instant.now() else null)
                        // The token is only needed while the run can still be resumed (spec §6.2), or while syncing.
                        .apply { if (syncUntil == null) set(IMPORT_JOBS.TOKEN_ENCRYPTED, null as String?) }
                        .where(IMPORT_JOBS.ID.eq(job.id)).execute()
                }
            } catch (e: HarvestAuthException) {
                fail(job.id, e.message ?: "Harvest rejected the token")
            } catch (e: Exception) {
                log.warn("Harvest import {} stopped in {}: {}", job.id, run.progress.step, e.toString(), e)
                fail(job.id, "The import stopped while importing ${run.progress.step ?: "data"}: ${e.message}. Resume it to continue from where it stopped.")
            }
        }
    }

    private fun fail(jobId: UUID, message: String) = tx.run {
        dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "failed").set(IMPORT_JOBS.ERROR, message).where(IMPORT_JOBS.ID.eq(jobId)).execute()
    }

    /** Syncs every job in its sync window; ends the windows that are over. Runs every [HarvestSettings.syncInterval]. */
    fun syncAll() {
        val jobs = tx.system { dsl.select(IMPORT_JOBS.ID).from(IMPORT_JOBS).where(IMPORT_JOBS.STATUS.eq("syncing")).fetch(IMPORT_JOBS.ID) }
        jobs.forEach { id ->
            try {
                sync(id)
            } catch (e: Exception) {
                log.warn("Harvest sync {} failed: {}", id, e.toString())
            }
        }
    }

    /**
     * Brings over what changed in Harvest since the last sync (spec §6, step 4): every list asked
     * for with updated_since, written through the same idempotent upserts as the import. Harvest
     * is the source while both run side by side, except that entries invoiced or timing here are
     * left alone. The last-synced time moves only after a sync succeeds, so a failed one is
     * simply repeated next time.
     */
    fun sync(jobId: UUID) {
        val job = tx.system { dsl.selectFrom(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(jobId)).fetchOne() } ?: return
        if (job.status != "syncing") return
        if (job.syncUntil == null || job.syncUntil.isBefore(Instant.now())) return endSync(jobId, null)
        val token = job.tokenEncrypted?.let(secrets::decrypt) ?: return endSync(jobId, "The Harvest token is no longer stored; syncing stopped.")
        val started = Instant.now()
        DbContext.forAccount(job.accountId) {
            val since = job.lastSyncedAt ?: job.startedAt ?: started
            val run = Run(job, clientFactory.create(token, job.externalAccountId ?: ""), ImportProgress(phase = "sync"), updatedSince = since)
            tx.run {
                val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(job.accountId)).fetchOne()!!
                run.accountCurrency = account.defaultCurrency
                run.weekStart = DayOfWeek.of(account.weekStart.toInt())
            }
            try {
                syncSteps.forEach { (_, _, body) -> body(run) }
                tx.run {
                    val stats = json.readTree(job.stats.data()) as com.fasterxml.jackson.databind.node.ObjectNode
                    stats.set<JsonNode>("last_sync", json.valueToTree(run.progress.counts))
                    dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.LAST_SYNCED_AT, started).set(IMPORT_JOBS.ERROR, null as String?)
                        .set(IMPORT_JOBS.STATS, JSONB.valueOf(json.writeValueAsString(stats)))
                        .where(IMPORT_JOBS.ID.eq(jobId)).and(IMPORT_JOBS.STATUS.eq("syncing")).execute()
                }
            } catch (e: HarvestAuthException) {
                endSync(jobId, "Harvest no longer accepts the token, so syncing stopped. Everything synced until then stays.")
            } catch (e: Exception) {
                log.warn("Harvest sync {} stopped in {}: {}", jobId, run.progress.step, e.toString())
                tx.system { dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.ERROR, "The last sync stopped (${e.message}); it is tried again in a few minutes.").where(IMPORT_JOBS.ID.eq(jobId)).execute() }
            }
        }
    }

    /** The cutover: syncing stops and the token is forgotten. */
    fun endSync(jobId: UUID, error: String?) = tx.system {
        dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "completed").set(IMPORT_JOBS.TOKEN_ENCRYPTED, null as String?)
            .set(IMPORT_JOBS.SYNC_UNTIL, DSL.least(DSL.coalesce(IMPORT_JOBS.SYNC_UNTIL, DSL.currentInstant()), DSL.currentInstant()))
            .set(IMPORT_JOBS.ERROR, error)
            .where(IMPORT_JOBS.ID.eq(jobId)).and(IMPORT_JOBS.STATUS.eq("syncing")).execute()
        Unit
    }

    private fun cancelled(jobId: UUID) = tx.run { dsl.select(IMPORT_JOBS.STATUS).from(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(jobId)).fetchOne()?.value1() == "cancelled" }

    private fun readProgress(job: ImportJobsRecord): ImportProgress =
        runCatching { json.readValue(job.progress.data(), ImportProgress::class.java) }.getOrDefault(ImportProgress())

    private fun saveProgress(run: Run) {
        dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.PROGRESS, JSONB.valueOf(json.writeValueAsString(run.progress))).set(IMPORT_JOBS.PHASE, run.progress.phase)
            .where(IMPORT_JOBS.ID.eq(run.job.id)).execute()
    }

    private fun issue(run: Run, entityType: String, externalId: Any?, reason: String, severity: String = "error", payload: JsonNode? = null) {
        dsl.insertInto(IMPORT_ISSUES).set(IMPORT_ISSUES.ACCOUNT_ID, run.accountId).set(IMPORT_ISSUES.JOB_ID, run.job.id)
            .set(IMPORT_ISSUES.SEVERITY, severity).set(IMPORT_ISSUES.ENTITY_TYPE, entityType).set(IMPORT_ISSUES.EXTERNAL_ID, externalId?.toString())
            .set(IMPORT_ISSUES.REASON, reason).set(IMPORT_ISSUES.PAYLOAD, payload?.let { JSONB.valueOf(it.toString()) })
            .execute()
    }

    /** Fetches every page of one step, resuming from the checkpoint; each page commits with its checkpoint. */
    private fun <T> paged(run: Run, step: String, phase: String, firstUrl: String, key: String, type: Class<T>, handle: (List<T>, List<JsonNode>) -> Unit) {
        var url: String? = if (!run.syncing && run.progress.step == step && run.progress.next != null) run.progress.next else firstUrl
        while (url != null) {
            val page = run.client.page(url, key, type)
            tx.run {
                handle(page.items, page.raw)
                val counts = run.progress.counts + (step to (run.progress.counts[step] ?: 0) + page.items.size)
                run.progress = run.progress.copy(phase = phase, step = step, next = page.nextUrl, counts = counts)
                // A sync starts over from its last-synced time if it fails, so it keeps no checkpoint.
                if (!run.syncing) saveProgress(run)
            }
            url = page.nextUrl
        }
    }

    private fun list(run: Run, path: String, params: Map<String, Any?> = emptyMap()) =
        run.client.url(path, mapOf("per_page" to settings.pageSize) + params + (if (run.syncing) mapOf("updated_since" to run.updatedSince.toString()) else emptyMap()))

    private val steps: List<Triple<String, String, (Run) -> Unit>> = listOf(
        Triple("company", "structure", ::importCompany),
        Triple("users", "structure", ::importUsers),
        Triple("roles", "structure", ::importRoles),
        Triple("clients", "structure", ::importClients),
        Triple("contacts", "structure", ::importContacts),
        Triple("tasks", "structure", ::importTasks),
        Triple("projects", "structure", ::importProjects),
        Triple("task_assignments", "structure", ::importTaskAssignments),
        Triple("user_assignments", "structure", ::importUserAssignments),
        Triple("expense_categories", "structure", ::importExpenseCategories),
        Triple("recent_time_entries", "recent", { r -> importTimeEntries(r, "recent_time_entries", "recent", from = r.recentFrom, to = null) }),
        Triple("recent_expenses", "recent", { r -> importExpenses(r, "recent_expenses", "recent", from = r.recentFrom, to = null) }),
        Triple("open_invoices", "recent", { r -> importInvoices(r, "open_invoices", "recent", state = "open") }),
        Triple("history_time_entries", "history", { r -> importTimeEntries(r, "history_time_entries", "history", from = null, to = r.recentFrom.minusDays(1)) }),
        Triple("history_expenses", "history", { r -> importExpenses(r, "history_expenses", "history", from = null, to = r.recentFrom.minusDays(1)) }),
        Triple("invoices", "history", { r -> importInvoices(r, "invoices", "history", state = null) }),
        Triple("estimates", "history", ::importEstimates),
        Triple("finish", "history", ::finish),
        Triple("verification", "verification", { r ->
            tx.run {
                val v = verifier.verify(r.job.id, r.accountId, r.client)
                funnel.event(r.accountId, Funnel.IMPORT_VERIFIED, mapOf("source" to "harvest", "all_match" to v.allMatch))
            }
        }),
    )

    /** What a sync asks Harvest for: everything an import does except the company, roles, estimates and the verification. */
    private val syncSteps: List<Triple<String, String, (Run) -> Unit>> = listOf(
        Triple("users", "sync", ::importUsers),
        Triple("clients", "sync", ::importClients),
        Triple("contacts", "sync", ::importContacts),
        Triple("tasks", "sync", ::importTasks),
        Triple("projects", "sync", ::importProjects),
        Triple("task_assignments", "sync", ::importTaskAssignments),
        Triple("user_assignments", "sync", ::importUserAssignments),
        Triple("expense_categories", "sync", ::importExpenseCategories),
        Triple("time_entries", "sync", { r -> importTimeEntries(r, "time_entries", "sync", from = null, to = null) }),
        Triple("expenses", "sync", { r -> importExpenses(r, "expenses", "sync", from = null, to = null) }),
        Triple("invoices", "sync", { r -> importInvoices(r, "invoices", "sync", state = null) }),
        Triple("finish", "sync", ::finish),
    )

    /** While syncing, an entry invoiced here (not on an invoice brought over from Harvest) keeps what it was invoiced with. */
    private fun invoicedHere(invoiceId: UUID?): Boolean = invoiceId != null && !dsl.fetchExists(
        com.honestrobin.time.db.Tables.EXTERNAL_LINKS,
        com.honestrobin.time.db.Tables.EXTERNAL_LINKS.SYSTEM.eq("harvest").and(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_TYPE.eq("invoice"))
            .and(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_ID.eq(invoiceId)),
    )

    // ---------------------------------------------------------------- structure

    private fun importCompany(run: Run) = tx.run {
        val company = run.client.single("/company", HCompany::class.java)
        val update = dsl.update(ACCOUNTS).set(ACCOUNTS.UPDATED_AT, Instant.now(clock))
        company.weekStartDay?.let { day ->
            runCatching { DayOfWeek.valueOf(day.uppercase()) }.getOrNull()?.let {
                update.set(ACCOUNTS.WEEK_START, it.value.toShort())
                run.weekStart = it
            }
        }
        when (company.timeFormat) {
            "decimal" -> update.set(ACCOUNTS.DURATION_FORMAT, "decimal")
            "hours_minutes" -> update.set(ACCOUNTS.DURATION_FORMAT, "hm")
        }
        company.approvalFeature?.let { update.set(ACCOUNTS.APPROVALS_ENABLED, it) }
        update.where(ACCOUNTS.ID.eq(run.accountId)).execute()
        run.progress = run.progress.copy(phase = "structure", step = "company", counts = run.progress.counts + ("company" to 1))
        saveProgress(run)
    }

    private fun importUsers(run: Run) = paged(run, "users", "structure", list(run, "/users"), "users", HUser::class.java) { users, _ ->
        val existing = run.links.find("user", users.map { it.id.toString() })
        users.forEach { u ->
            val address = u.email?.trim()?.lowercase()
            if (address.isNullOrBlank()) {
                issue(run, "user", u.id, "Harvest user ${u.fullName} has no email address")
                return@forEach
            }
            val id = existing[u.id.toString()]
                ?: dsl.select(MEMBERSHIPS.ID).from(MEMBERSHIPS).where(DSL.lower(MEMBERSHIPS.EMAIL).eq(address)).fetchOne()?.value1()
            val r = id?.let { dsl.selectFrom(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(MEMBERSHIPS).apply {
                accountId = run.accountId
                email = address
                status = "pending_invite"
            }
            val signedIn = r.userId != null
            r.name = u.fullName
            if (!signedIn) {
                // People who already use this account keep the access they were given here.
                val roles = u.accessRoles.toSet()
                r.role = when {
                    "administrator" in roles -> "admin"
                    "manager" in roles -> "manager"
                    else -> "member"
                }
                val admin = r.role == "admin"
                r.canSeeRates = admin || "billable_rates_manager" in roles
                r.canManageProjects = admin || "project_creator" in roles
                r.canManageInvoices = admin || "managed_projects_invoice_manager" in roles
                r.isActive = u.isActive
            }
            r.isContractor = u.isContractor
            r.hasAccessToAllFutureProjects = u.hasAccessToAllFutureProjects
            u.weeklyCapacity?.let { r.weeklyCapacitySeconds = it }
            r.defaultBillableRate = u.defaultHourlyRate?.let { Money.toMinor(it, run.accountCurrency) }
            r.costRate = u.costRate?.let { Money.toMinor(it, run.accountCurrency) }
            r.store()
            run.links.put("user", u.id.toString(), r.id)
        }
    }

    private fun importRoles(run: Run) = paged(run, "roles", "structure", list(run, "/roles"), "roles", HRole::class.java) { roles, _ ->
        roles.forEach { role ->
            val teamId = upsertByName(run, "role", role.id, TEAMS.ID, { dsl.select(TEAMS.ID).from(TEAMS).where(DSL.lower(TEAMS.NAME).eq(role.name.lowercase())) }) { id ->
                val r = id?.let { dsl.selectFrom(TEAMS).where(TEAMS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(TEAMS).apply { accountId = run.accountId }
                r.name = role.name.trim().take(200)
                r.store()
                r.id
            }
            val members = run.links.find("user", role.userIds.map(Long::toString)).values
            dsl.deleteFrom(TEAM_MEMBERSHIPS).where(TEAM_MEMBERSHIPS.TEAM_ID.eq(teamId)).execute()
            members.forEach { m ->
                dsl.insertInto(TEAM_MEMBERSHIPS).set(TEAM_MEMBERSHIPS.ACCOUNT_ID, run.accountId).set(TEAM_MEMBERSHIPS.TEAM_ID, teamId)
                    .set(TEAM_MEMBERSHIPS.MEMBERSHIP_ID, m).onConflictDoNothing().execute()
            }
        }
    }

    /** Finds the row linked to [externalId], or else one with the same name, then writes it with [write] and links it. */
    private fun upsertByName(run: Run, type: String, externalId: Long, idField: org.jooq.Field<UUID>, byName: () -> org.jooq.ResultQuery<org.jooq.Record1<UUID>>, write: (UUID?) -> UUID): UUID {
        val linked = run.links.get(type, externalId)
        val id = write(linked ?: byName().fetchOne()?.get(idField))
        run.links.put(type, externalId.toString(), id)
        return id
    }

    private fun importClients(run: Run) = paged(run, "clients", "structure", list(run, "/clients"), "clients", HClient::class.java) { clients, _ ->
        clients.forEach { c ->
            upsertByName(run, "client", c.id, CLIENTS.ID, { dsl.select(CLIENTS.ID).from(CLIENTS).where(DSL.lower(CLIENTS.NAME).eq(c.name.trim().lowercase())) }) { id ->
                val r = id?.let { dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(CLIENTS).apply { accountId = run.accountId }
                r.name = c.name.trim().take(200)
                r.currency = c.currency?.takeIf { runCatching { java.util.Currency.getInstance(it) }.isSuccess } ?: run.accountCurrency
                val lines = c.address?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                r.addressLine1 = lines.firstOrNull()
                r.addressLine2 = lines.drop(1).joinToString(", ").ifBlank { null }
                r.isActive = c.isActive
                r.archivedAt = if (c.isActive) null else r.archivedAt ?: Instant.now(clock)
                r.store()
                r.id
            }
        }
    }

    private fun importContacts(run: Run) = paged(run, "contacts", "structure", list(run, "/contacts"), "contacts", HContact::class.java) { contacts, _ ->
        contacts.forEach { c ->
            val clientId = run.links.get("client", c.client.id) ?: return@forEach issue(run, "contact", c.id, "Contact belongs to a client that was not imported")
            val id = run.links.get("contact", c.id)
            val r = id?.let { dsl.selectFrom(CLIENT_CONTACTS).where(CLIENT_CONTACTS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(CLIENT_CONTACTS).apply { accountId = run.accountId }
            r.clientId = clientId
            r.name = listOfNotNull(c.firstName, c.lastName).joinToString(" ").ifBlank { c.email ?: "Contact ${c.id}" }.take(200)
            r.title = c.title
            r.email = c.email
            r.phone = c.phoneOffice?.ifBlank { null } ?: c.phoneMobile
            r.store()
            run.links.put("contact", c.id.toString(), r.id)
        }
    }

    private fun importTasks(run: Run) = paged(run, "tasks", "structure", list(run, "/tasks"), "tasks", HTask::class.java) { tasks, _ ->
        tasks.forEach { t ->
            upsertByName(run, "task", t.id, TASKS.ID, { dsl.select(TASKS.ID).from(TASKS).where(DSL.lower(TASKS.NAME).eq(t.name.trim().lowercase())) }) { id ->
                val r = id?.let { dsl.selectFrom(TASKS).where(TASKS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(TASKS).apply { accountId = run.accountId }
                r.name = t.name.trim().take(200)
                r.isDefault = t.isDefault
                r.defaultBillable = t.billableByDefault
                r.defaultRate = t.defaultHourlyRate?.let { Money.toMinor(it, run.accountCurrency) }
                r.isActive = t.isActive
                r.archivedAt = if (t.isActive) null else r.archivedAt ?: Instant.now(clock)
                r.store()
                r.id
            }
        }
    }

    private fun importProjects(run: Run) = paged(run, "projects", "structure", list(run, "/projects"), "projects", HProject::class.java) { projects, _ ->
        projects.forEach { p ->
            val clientId = run.links.get("client", p.client.id) ?: return@forEach issue(run, "project", p.id, "Project belongs to a client that was not imported")
            val currency = dsl.select(CLIENTS.CURRENCY).from(CLIENTS).where(CLIENTS.ID.eq(clientId)).fetchOne()!!.value1()
            upsertByName(run, "project", p.id, PROJECTS.ID, {
                dsl.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.CLIENT_ID.eq(clientId)).and(DSL.lower(PROJECTS.NAME).eq(p.name.trim().lowercase()))
            }) { id ->
                val r = id?.let { dsl.selectFrom(PROJECTS).where(PROJECTS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(PROJECTS).apply { accountId = run.accountId }
                r.clientId = clientId
                r.name = p.name.trim().take(200)
                r.code = p.code?.ifBlank { null }
                r.isBillable = p.isBillable
                r.billBy = when (p.billBy.lowercase()) {
                    "project" -> "project"
                    "tasks" -> "tasks"
                    "people" -> "people"
                    else -> "none"
                }
                r.hourlyRate = p.hourlyRate?.let { Money.toMinor(it, currency) }
                r.budgetBy = p.budgetBy.takeIf { it in setOf("project", "project_cost", "task", "task_fees", "person") } ?: "none"
                r.budgetIsMonthly = p.budgetIsMonthly
                r.budgetSeconds = if (r.budgetBy == "project") p.budget?.let(::hoursToSeconds) else null
                r.budgetAmount = if (r.budgetBy == "project_cost") p.costBudget?.let { Money.toMinor(it, currency) } else null
                r.budgetIncludeExpenses = p.costBudgetIncludeExpenses
                r.budgetAlertPercent = p.overBudgetNotificationPercentage?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal(100) }
                r.notifyWhenOverBudget = p.notifyWhenOverBudget
                r.showBudgetToAll = p.showBudgetToAll
                r.isFixedFee = p.isFixedFee
                r.feeAmount = if (p.isFixedFee) p.fee?.let { Money.toMinor(it, currency) } else null
                r.startsOn = p.startsOn
                r.endsOn = p.endsOn
                r.notes = p.notes
                r.isActive = p.isActive
                r.archivedAt = if (p.isActive) null else r.archivedAt ?: Instant.now(clock)
                r.store()
                run.projectCurrency[r.id] = currency
                r.id
            }
        }
    }

    private fun currencyOf(run: Run, projectId: UUID): String = run.projectCurrency.getOrPut(projectId) {
        dsl.select(CLIENTS.CURRENCY).from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID)).where(PROJECTS.ID.eq(projectId)).fetchOne()!!.value1()
    }

    private fun importTaskAssignments(run: Run) =
        paged(run, "task_assignments", "structure", list(run, "/task_assignments"), "task_assignments", HTaskAssignment::class.java) { rows, _ ->
            rows.forEach { a ->
                val projectId = run.links.get("project", a.project.id) ?: return@forEach issue(run, "task_assignment", a.id, "Assigned project was not imported")
                val taskId = run.links.get("task", a.task.id) ?: return@forEach issue(run, "task_assignment", a.id, "Assigned task was not imported")
                val currency = currencyOf(run, projectId)
                val budgetBy = dsl.select(PROJECTS.BUDGET_BY).from(PROJECTS).where(PROJECTS.ID.eq(projectId)).fetchOne()!!.value1()
                val id = run.links.get("task_assignment", a.id)
                    ?: dsl.select(PROJECT_TASKS.ID).from(PROJECT_TASKS).where(PROJECT_TASKS.PROJECT_ID.eq(projectId)).and(PROJECT_TASKS.TASK_ID.eq(taskId)).fetchOne()?.value1()
                val r = id?.let { dsl.selectFrom(PROJECT_TASKS).where(PROJECT_TASKS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(PROJECT_TASKS).apply {
                    accountId = run.accountId
                    this.projectId = projectId
                    this.taskId = taskId
                }
                r.billable = a.billable
                r.hourlyRate = a.hourlyRate?.let { Money.toMinor(it, currency) }
                r.budgetSeconds = if (budgetBy == "task") a.budget?.let(::hoursToSeconds) else null
                r.budgetAmount = if (budgetBy == "task_fees") a.budget?.let { Money.toMinor(it, currency) } else null
                r.isActive = a.isActive
                r.store()
                run.links.put("task_assignment", a.id.toString(), r.id)
            }
        }

    private fun importUserAssignments(run: Run) =
        paged(run, "user_assignments", "structure", list(run, "/user_assignments"), "user_assignments", HUserAssignment::class.java) { rows, _ ->
            rows.forEach { a ->
                val projectId = run.links.get("project", a.project.id) ?: return@forEach issue(run, "user_assignment", a.id, "Assigned project was not imported")
                val membershipId = run.links.get("user", a.user.id) ?: return@forEach issue(run, "user_assignment", a.id, "Assigned person was not imported")
                val currency = currencyOf(run, projectId)
                val id = run.links.get("user_assignment", a.id)
                    ?: dsl.select(PROJECT_MEMBERS.ID).from(PROJECT_MEMBERS).where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId)).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(membershipId)).fetchOne()?.value1()
                val r = id?.let { dsl.selectFrom(PROJECT_MEMBERS).where(PROJECT_MEMBERS.ID.eq(it)).fetchOne() } ?: dsl.newRecord(PROJECT_MEMBERS).apply {
                    accountId = run.accountId
                    this.projectId = projectId
                    this.membershipId = membershipId
                }
                r.isManager = a.isProjectManager
                r.useDefaultRates = a.useDefaultRates
                r.hourlyRate = a.hourlyRate?.let { Money.toMinor(it, currency) }
                r.budgetSeconds = a.budget?.let(::hoursToSeconds)
                r.isActive = a.isActive
                r.store()
                run.links.put("user_assignment", a.id.toString(), r.id)
            }
        }

    private fun importExpenseCategories(run: Run) =
        paged(run, "expense_categories", "structure", list(run, "/expense_categories"), "expense_categories", HExpenseCategory::class.java) { rows, _ ->
            rows.forEach { c ->
                upsertByName(run, "expense_category", c.id, EXPENSE_CATEGORIES.ID, {
                    dsl.select(EXPENSE_CATEGORIES.ID).from(EXPENSE_CATEGORIES).where(DSL.lower(EXPENSE_CATEGORIES.NAME).eq(c.name.trim().lowercase()))
                }) { id ->
                    val r = id?.let { dsl.selectFrom(EXPENSE_CATEGORIES).where(EXPENSE_CATEGORIES.ID.eq(it)).fetchOne() } ?: dsl.newRecord(EXPENSE_CATEGORIES).apply { accountId = run.accountId }
                    r.name = c.name.trim().take(200)
                    r.unitName = c.unitName?.ifBlank { null }
                    r.unitPrice = c.unitPrice?.let { Money.toMinor(it, run.accountCurrency) }
                    r.isActive = c.isActive
                    r.archivedAt = if (c.isActive) null else r.archivedAt ?: Instant.now(clock)
                    r.store()
                    r.id
                }
            }
        }

    // ---------------------------------------------------------------- work

    private fun importTimeEntries(run: Run, step: String, phase: String, from: LocalDate?, to: LocalDate?) =
        paged(run, step, phase, list(run, "/time_entries", mapOf("from" to from, "to" to to)), "time_entries", HTimeEntry::class.java) { entries, raw ->
            val existing = run.links.find("time_entry", entries.map { it.id.toString() })
            val records = existing.values.takeIf { it.isNotEmpty() }?.let { ids -> dsl.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.ID.`in`(ids)).fetch().associateBy { it.id } }.orEmpty()
            val invoiceRefs = mutableListOf<Triple<String, UUID, String?>>()
            entries.forEachIndexed { i, e ->
                val membershipId = run.links.get("user", e.user.id) ?: return@forEachIndexed issue(run, "time_entry", e.id, "The person was not imported", payload = raw[i])
                val projectId = run.links.get("project", e.project.id) ?: return@forEachIndexed issue(run, "time_entry", e.id, "The project was not imported", payload = raw[i])
                val taskId = run.links.get("task", e.task.id) ?: return@forEachIndexed issue(run, "time_entry", e.id, "The task was not imported", payload = raw[i])
                val currency = currencyOf(run, projectId)
                val found = existing[e.id.toString()]?.let(records::get)
                if (run.syncing && found != null && (found.timerStartedAt != null || invoicedHere(found.invoiceId))) {
                    return@forEachIndexed issue(run, "time_entry", e.id, "Changed in Harvest after it was invoiced or started here; kept as it is here", "warning")
                }
                val r = found ?: dsl.newRecord(TIME_ENTRIES).apply { accountId = run.accountId }
                r.membershipId = membershipId
                r.projectId = projectId
                r.taskId = taskId
                r.spentDate = e.spentDate
                val seconds = hoursToSeconds(e.hours)
                if (seconds > 86_400) issue(run, "time_entry", e.id, "Longer than 24 hours (${e.hours} h); capped at 24 hours", "warning")
                r.durationSeconds = seconds.coerceIn(0, 86_400).toInt()
                // A running Harvest timer comes over stopped, with the time it had so far (spec §6.4).
                if (e.isRunning) issue(run, "time_entry", e.id, "This timer was running in Harvest; it was imported stopped at ${e.hours} h", "warning")
                r.timerStartedAt = null
                r.startTime = parseClock(e.startedTime)
                r.endTime = if (e.isRunning) null else parseClock(e.endedTime)
                r.notes = e.notes
                r.billable = e.billable
                r.billableRateSnapshot = if (e.billable) e.billableRate?.let { Money.toMinor(it, currency) } ?: 0 else 0
                r.costRateSnapshot = e.costRate?.let { Money.toMinor(it, run.accountCurrency) } ?: 0
                e.externalReference?.let { x ->
                    r.externalSource = x.service?.take(50)
                    r.externalId = x.id?.take(200)
                    r.externalGroupId = x.groupId?.take(200)
                    r.externalUrl = x.permalink?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(2000)
                }
                r.approvalState = when (e.approvalStatus) {
                    "submitted" -> "submitted"
                    "approved" -> "approved"
                    else -> "draft"
                }
                r.isLocked = e.isLocked || e.isBilled
                r.lockedReason = e.lockedReason ?: if (e.isBilled) "Invoiced in Harvest" else null
                r.store()
                invoiceRefs += Triple(e.id.toString(), r.id, e.invoice?.let { """{"invoice":"${it.id}"}""" })
            }
            run.links.putAll("time_entry", invoiceRefs)
            // Entries on invoices imported earlier get their link now; the rest when their invoice arrives.
            attachInvoices(run, TIME_ENTRIES.INVOICE_ID, TIME_ENTRIES.ID, invoiceRefs)
        }

    private fun importExpenses(run: Run, step: String, phase: String, from: LocalDate?, to: LocalDate?) =
        paged(run, step, phase, list(run, "/expenses", mapOf("from" to from, "to" to to)), "expenses", HExpense::class.java) { expenses, raw ->
            val refs = mutableListOf<Triple<String, UUID, String?>>()
            expenses.forEachIndexed { i, x ->
                val membershipId = run.links.get("user", x.user.id) ?: return@forEachIndexed issue(run, "expense", x.id, "The person was not imported", payload = raw[i])
                val projectId = run.links.get("project", x.project.id) ?: return@forEachIndexed issue(run, "expense", x.id, "The project was not imported", payload = raw[i])
                val categoryId = run.links.get("expense_category", x.expenseCategory.id)
                    ?: return@forEachIndexed issue(run, "expense", x.id, "The expense category was not imported", payload = raw[i])
                val currency = currencyOf(run, projectId)
                val id = run.links.get("expense", x.id)
                val found = id?.let { dsl.selectFrom(EXPENSES).where(EXPENSES.ID.eq(it)).fetchOne() }
                if (run.syncing && found != null && invoicedHere(found.invoiceId)) {
                    return@forEachIndexed issue(run, "expense", x.id, "Changed in Harvest after it was invoiced here; kept as it is here", "warning")
                }
                val r = found ?: dsl.newRecord(EXPENSES).apply { accountId = run.accountId }
                r.membershipId = membershipId
                r.projectId = projectId
                r.categoryId = categoryId
                r.spentDate = x.spentDate
                r.amountMinor = Money.toMinor(x.totalCost, currency)
                r.currency = currency
                r.units = x.units?.setScale(2, RoundingMode.HALF_UP)
                r.notes = x.notes
                r.billable = x.billable
                r.approvalState = when (x.approvalStatus) {
                    "submitted" -> "submitted"
                    "approved" -> "approved"
                    else -> "draft"
                }
                r.isLocked = x.isLocked || x.isBilled
                r.lockedReason = x.lockedReason ?: if (x.isBilled) "Invoiced in Harvest" else null
                if (r.receiptFileId == null && run.importReceipts && x.receipt?.url != null) {
                    runCatching {
                        val (bytes, type) = run.client.download(x.receipt.url)
                        files.store(run.accountId, x.receipt.fileName ?: "receipt-${x.id}", x.receipt.contentType ?: type ?: "application/octet-stream", bytes, null).id
                    }.onSuccess { r.receiptFileId = it }
                        .onFailure { issue(run, "expense", x.id, "The receipt could not be downloaded: ${it.message}", "warning") }
                }
                r.store()
                refs += Triple(x.id.toString(), r.id, x.invoice?.let { """{"invoice":"${it.id}"}""" })
            }
            run.links.putAll("expense", refs)
            attachInvoices(run, EXPENSES.INVOICE_ID, EXPENSES.ID, refs)
        }

    private fun <R : org.jooq.Record> attachInvoices(run: Run, invoiceField: org.jooq.TableField<R, UUID>, idField: org.jooq.TableField<R, UUID>, refs: List<Triple<String, UUID, String?>>) {
        val byInvoice = refs.mapNotNull { (_, id, meta) -> meta?.let { json.readTree(it)["invoice"]?.asText() }?.let { it to id } }.groupBy({ it.first }, { it.second })
        if (byInvoice.isEmpty()) return
        val invoices = run.links.find("invoice", byInvoice.keys)
        invoices.forEach { (externalId, invoiceId) ->
            dsl.update(idField.table!!).set(invoiceField, invoiceId).where(idField.`in`(byInvoice.getValue(externalId))).execute()
        }
    }

    private fun importInvoices(run: Run, step: String, phase: String, state: String?) =
        paged(run, step, phase, list(run, "/invoices", mapOf("state" to state)), "invoices", HInvoice::class.java) { invoices, raw ->
            invoices.forEachIndexed { i, inv ->
                val clientId = run.links.get("client", inv.client.id) ?: return@forEachIndexed issue(run, "invoice", inv.id, "The client was not imported", payload = raw[i])
                val currency = inv.currency.takeIf { runCatching { java.util.Currency.getInstance(it) }.isSuccess } ?: run.accountCurrency
                val m = { v: BigDecimal? -> v?.let { Money.toMinor(it, currency) } ?: 0L }
                val id = run.links.get("invoice", inv.id)
                val number = inv.number?.trim()?.ifBlank { null }
                if (id == null && number != null && dsl.fetchExists(INVOICES, INVOICES.NUMBER.eq(number))) {
                    return@forEachIndexed issue(run, "invoice", inv.id, "An invoice numbered $number already exists in this account", payload = raw[i])
                }
                val r = id?.let { dsl.selectFrom(INVOICES).where(INVOICES.ID.eq(it)).fetchOne() } ?: dsl.newRecord(INVOICES).apply { accountId = run.accountId }
                r.clientId = clientId
                r.number = number
                r.issueDate = inv.issueDate ?: inv.createdAt?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: run.today
                r.dueDate = inv.dueDate
                r.currency = currency
                val paid = m(inv.amount) - m(inv.dueAmount)
                r.state = when (inv.state) {
                    "paid" -> "paid"
                    "closed" -> "void"
                    "open" -> if (paid > 0) "partially_paid" else "sent"
                    else -> "draft"
                }
                r.subject = inv.subject
                r.notes = inv.notes
                r.purchaseOrder = inv.purchaseOrder
                r.tax1Name = inv.tax?.let { "Tax" }
                r.tax1Percent = inv.tax
                r.tax2Name = inv.tax2?.let { "Tax 2" }
                r.tax2Percent = inv.tax2
                r.discountPercent = inv.discount
                r.subtotalMinor = inv.lineItems.sumOf { m(it.amount) }
                r.discountMinor = m(inv.discountAmount)
                r.tax1Minor = m(inv.taxAmount)
                r.tax2Minor = m(inv.tax2Amount)
                r.totalMinor = m(inv.amount)
                r.dueMinor = m(inv.dueAmount)
                r.paidMinor = paid
                r.periodStart = inv.periodStart
                r.periodEnd = inv.periodEnd
                r.paymentTerms = inv.paymentTerm
                r.source = "harvest_import"
                r.isReadOnly = true
                r.sentAt = inv.sentAt
                r.paidAt = inv.paidAt
                r.voidedAt = if (inv.state == "closed") inv.closedAt else null
                r.store()
                run.links.put("invoice", inv.id.toString(), r.id)

                dsl.deleteFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(r.id)).execute()
                inv.lineItems.forEachIndexed { pos, line ->
                    dsl.insertInto(INVOICE_LINES).set(INVOICE_LINES.ACCOUNT_ID, run.accountId).set(INVOICE_LINES.INVOICE_ID, r.id)
                        .set(INVOICE_LINES.POSITION, pos)
                        .set(INVOICE_LINES.KIND, when (line.kind?.lowercase()) { "product" -> "product"; "expense" -> "expense"; else -> "service" })
                        .set(INVOICE_LINES.KIND_LABEL, line.kind)
                        .set(INVOICE_LINES.PROJECT_ID, line.project?.let { run.links.get("project", it.id) })
                        .set(INVOICE_LINES.DESCRIPTION, line.description)
                        .set(INVOICE_LINES.QUANTITY, line.quantity)
                        .set(INVOICE_LINES.UNIT_PRICE_MINOR, m(line.unitPrice))
                        .set(INVOICE_LINES.TAX1_APPLIES, line.taxed)
                        .set(INVOICE_LINES.TAX2_APPLIES, line.taxed2)
                        .set(INVOICE_LINES.LINE_TOTAL_MINOR, m(line.amount))
                        .execute()
                }

                if (inv.state != "draft") {
                    val payments = run.client.all(run.client.url("/invoices/${inv.id}/payments", mapOf("per_page" to settings.pageSize)), "invoice_payments", HPayment::class.java)
                    dsl.deleteFrom(PAYMENTS).where(PAYMENTS.INVOICE_ID.eq(r.id)).execute()
                    payments.forEach { p ->
                        dsl.insertInto(PAYMENTS).set(PAYMENTS.ACCOUNT_ID, run.accountId).set(PAYMENTS.INVOICE_ID, r.id)
                            .set(PAYMENTS.AMOUNT_MINOR, m(p.amount))
                            .set(PAYMENTS.PAID_AT, p.paidAt)
                            .set(PAYMENTS.PAID_DATE, p.paidDate ?: p.paidAt?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: r.issueDate)
                            .set(PAYMENTS.METHOD, if (p.paymentGateway != null) "other" else "manual")
                            .set(PAYMENTS.EXTERNAL_ID, p.transactionId)
                            .set(PAYMENTS.NOTES, p.notes)
                            .set(PAYMENTS.RECORDED_BY, p.recordedBy)
                            .execute()
                    }
                }

                // Time and expenses imported before this invoice point at it now.
                listOf("time_entry" to Pair(TIME_ENTRIES.ID, TIME_ENTRIES.INVOICE_ID), "expense" to Pair(EXPENSES.ID, EXPENSES.INVOICE_ID)).forEach { (type, fields) ->
                    val ids = linkedWithInvoice(run, type, inv.id)
                    if (ids.isNotEmpty()) dsl.update(fields.first.table!!).set(fields.second, r.id).where(fields.first.`in`(ids)).execute()
                }
            }
        }

    private fun linkedWithInvoice(run: Run, type: String, invoiceExternalId: Long): List<UUID> =
        dsl.select(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_ID).from(com.honestrobin.time.db.Tables.EXTERNAL_LINKS)
            .where(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.SYSTEM.eq("harvest"))
            .and(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_TYPE.eq(type))
            .and(DSL.field("{0} ->> 'invoice'", String::class.java, com.honestrobin.time.db.Tables.EXTERNAL_LINKS.META).eq(invoiceExternalId.toString()))
            .fetch(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_ID)

    /** Estimates have no counterpart yet; they are kept as read-only documents (spec §6.3). */
    private fun importEstimates(run: Run) = paged(run, "estimates", "history", list(run, "/estimates"), "estimates", JsonNode::class.java) { estimates, _ ->
        estimates.forEach { e ->
            val externalId = e["id"].asText()
            val clientId = e["client"]?.get("id")?.asLong()?.let { run.links.get("client", it) }
            dsl.insertInto(ARCHIVED_DOCUMENTS).set(ARCHIVED_DOCUMENTS.ACCOUNT_ID, run.accountId).set(ARCHIVED_DOCUMENTS.KIND, "harvest_estimate")
                .set(ARCHIVED_DOCUMENTS.EXTERNAL_ID, externalId).set(ARCHIVED_DOCUMENTS.NUMBER, e["number"]?.takeIf { !it.isNull }?.asText())
                .set(ARCHIVED_DOCUMENTS.ISSUE_DATE, e["issue_date"]?.takeIf { !it.isNull }?.asText()?.let(LocalDate::parse))
                .set(ARCHIVED_DOCUMENTS.CLIENT_ID, clientId).set(ARCHIVED_DOCUMENTS.DATA, JSONB.valueOf(e.toString()))
                .onConflict(ARCHIVED_DOCUMENTS.ACCOUNT_ID, ARCHIVED_DOCUMENTS.KIND, ARCHIVED_DOCUMENTS.EXTERNAL_ID)
                .doUpdate().set(ARCHIVED_DOCUMENTS.DATA, JSONB.valueOf(e.toString())).set(ARCHIVED_DOCUMENTS.CLIENT_ID, clientId)
                .execute()
        }
    }

    // ---------------------------------------------------------------- finish

    /** Submitted and approved weeks, and invoice numbering that continues after Harvest's (spec §6.4, AT-2.5). */
    private fun finish(run: Run) = tx.run {
        val weekly = dsl.select(TIME_ENTRIES.MEMBERSHIP_ID, TIME_ENTRIES.SPENT_DATE, TIME_ENTRIES.APPROVAL_STATE).from(TIME_ENTRIES)
            .where(TIME_ENTRIES.APPROVAL_STATE.`in`("submitted", "approved"))
            .and(TIME_ENTRIES.ID.`in`(importedIds("time_entry")))
            .fetch()
            .groupBy { it.value1() to it.value2().with(TemporalAdjusters.previousOrSame(run.weekStart)) }
        weekly.forEach { (key, rows) ->
            val state = if (rows.all { it.value3() == "approved" }) "approved" else "submitted"
            dsl.insertInto(TIMESHEET_SUBMISSIONS).set(TIMESHEET_SUBMISSIONS.ACCOUNT_ID, run.accountId).set(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID, key.first)
                .set(TIMESHEET_SUBMISSIONS.WEEK_START_DATE, key.second).set(TIMESHEET_SUBMISSIONS.STATE, state)
                .onConflict(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID, TIMESHEET_SUBMISSIONS.WEEK_START_DATE).doUpdate().set(TIMESHEET_SUBMISSIONS.STATE, state)
                .execute()
        }
        continueNumbering(run)
    }

    private fun importedIds(type: String) = dsl.select(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_ID).from(com.honestrobin.time.db.Tables.EXTERNAL_LINKS)
        .where(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.SYSTEM.eq("harvest")).and(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_TYPE.eq(type))

    private fun continueNumbering(run: Run) {
        val numbers = dsl.select(INVOICES.NUMBER).from(INVOICES).where(INVOICES.SOURCE.eq("harvest_import")).and(INVOICES.NUMBER.isNotNull).fetch(INVOICES.NUMBER)
        val highest = numbers.mapNotNull { n -> Regex("^(.*?)(\\d+)$").find(n)?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[2].toLong()) } }
            .maxByOrNull { it.third } ?: return
        val (prefix, digits, value) = highest
        val padding = if (digits.startsWith("0")) digits.length else 0
        val sequence = dsl.selectFrom(INVOICE_SEQUENCES).where(INVOICE_SEQUENCES.IS_DEFAULT.isTrue).fetchOne() ?: dsl.newRecord(INVOICE_SEQUENCES).apply {
            accountId = run.accountId
            name = "Default"
            isDefault = true
            this.prefix = prefix
            this.padding = padding.toShort()
        }
        if (sequence.nextNumber == null || sequence.nextNumber <= value) sequence.nextNumber = value + 1
        sequence.store()
    }

    companion object {
        fun hoursToSeconds(hours: BigDecimal): Long = hours.multiply(BigDecimal(3600)).setScale(0, RoundingMode.HALF_UP).toLong()

        /** Harvest gives clock times as "8:00am" or "08:00", depending on the company's clock setting. */
        fun parseClock(text: String?): LocalTime? {
            val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*([ap]m)?\\s*$", RegexOption.IGNORE_CASE).find(text ?: return null) ?: return null
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            when (m.groupValues[3].lowercase()) {
                "am" -> if (hour == 12) hour = 0
                "pm" -> if (hour != 12) hour += 12
            }
            return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        }
    }
}
