// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers

import com.honestrobin.time.analytics.Funnel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.honestrobin.time.db.Tables.IMPORT_ISSUES
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.tables.records.ImportJobsRecord
import com.honestrobin.time.importers.harvest.HCompany
import com.honestrobin.time.importers.harvest.HarvestApiException
import com.honestrobin.time.importers.harvest.HarvestAuthException
import com.honestrobin.time.importers.harvest.HarvestClientFactory
import com.honestrobin.time.importers.harvest.HarvestImporter
import com.honestrobin.time.importers.harvest.HarvestSettings
import com.honestrobin.time.platform.crypto.SecretBox
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.BadRequestException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import java.util.UUID

data class StartHarvestImport(
    /** A Harvest personal access token. */
    val token: String,
    /** The Harvest account ID shown next to the token. */
    val accountId: String,
    val importReceipts: Boolean = true,
    /**
     * Keep bringing over changes made in Harvest, every 15 minutes, until the end of this day
     * (at most 30 days ahead), so the team can use both while switching (spec §6, step 4).
     */
    val syncUntil: LocalDate? = null,
)

data class ImportJobView(
    val id: UUID,
    val source: String,
    val mode: String,
    val status: String,
    val phase: String?,
    val progress: JsonNode,
    val stats: JsonNode,
    val verification: JsonNode?,
    val error: String?,
    val externalAccountId: String?,
    val issueCount: Int,
    val canResume: Boolean,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val createdAt: Instant,
    /** While syncing (status "syncing"): when it stops; afterwards, when it stopped. */
    val syncUntil: Instant?,
    val lastSyncedAt: Instant?,
)

data class ImportIssueView(val id: UUID, val severity: String, val entityType: String, val externalId: String?, val reason: String, val createdAt: Instant)

/** Starting, following and resuming imports. Only admins import (spec §11). */
@Service
class ImportJobService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val settings: HarvestSettings,
    private val secrets: SecretBox,
    private val importer: HarvestImporter,
    private val clients: HarvestClientFactory,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @Qualifier("harvestImportTask") private val task: ObjectProvider<OneTimeTask<String>>,
    private val json: ObjectMapper,
    private val funnel: Funnel,
    private val clock: Clock,
) {
    fun startHarvest(m: Member, input: StartHarvestImport): ImportJobView {
        m.requireWritable()
        m.requireAdmin()
        val token = input.token.trim()
        val accountId = input.accountId.trim()
        val errors = mutableMapOf<String, String>()
        if (token.isEmpty()) errors["token"] = "Paste the personal access token from Harvest"
        if (!accountId.matches(Regex("^\\d+$"))) errors["account_id"] = "Use the numeric account ID shown next to the token in Harvest"
        val today = LocalDate.now(clock)
        if (input.syncUntil != null && (input.syncUntil.isBefore(today) || input.syncUntil.isAfter(today.plusDays(settings.maxSyncDays)))) {
            errors["sync_until"] = "Choose a day between today and ${settings.maxSyncDays} days from now"
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        if (tx.run { dsl.fetchExists(IMPORT_JOBS, IMPORT_JOBS.STATUS.`in`("queued", "running")) }) {
            throw ConflictException("import_running", "An import is already running for this account")
        }
        // Check the token before queueing, so a typo is reported here and not minutes later.
        try {
            clients.create(token, accountId).single("/company", HCompany::class.java)
        } catch (e: HarvestAuthException) {
            throw ValidationException(mapOf("token" to "Harvest did not accept this token for account $accountId"))
        } catch (e: HarvestApiException) {
            throw BadRequestException("harvest_unreachable", "Harvest could not be reached: ${e.message}")
        }
        val id = tx.run {
            dsl.insertInto(IMPORT_JOBS)
                .set(IMPORT_JOBS.ACCOUNT_ID, m.accountId).set(IMPORT_JOBS.SOURCE, "harvest").set(IMPORT_JOBS.MODE, "api").set(IMPORT_JOBS.STATUS, "queued")
                .set(IMPORT_JOBS.STARTED_BY, m.membershipId).set(IMPORT_JOBS.EXTERNAL_ACCOUNT_ID, accountId)
                .set(IMPORT_JOBS.TOKEN_ENCRYPTED, secrets.encrypt(token))
                .set(IMPORT_JOBS.SYNC_UNTIL, input.syncUntil?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant())
                .set(IMPORT_JOBS.OPTIONS, JSONB.valueOf(json.writeValueAsString(mapOf("import_receipts" to input.importReceipts))))
                .returning(IMPORT_JOBS.ID).fetchOne()!!.id
                .also { funnel.event(m.accountId, Funnel.IMPORT_STARTED, mapOf("source" to "harvest")) }
        }
        enqueue(id)
        return get(m, id)
    }

    fun resume(m: Member, id: UUID): ImportJobView {

        m.requireWritable()
        m.requireAdmin()
        tx.run {
            val job = load(id)
            if (job.status != "failed" || job.tokenEncrypted == null) throw ConflictException("not_resumable", "Only an import that stopped can be resumed")
            dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "queued").set(IMPORT_JOBS.ERROR, null as String?).where(IMPORT_JOBS.ID.eq(id)).execute()
        }
        enqueue(id)
        return get(m, id)
    }

    /**
     * Stops an import and forgets its Harvest token. Ending access only removes access, so it works
     * while the account is read-only ("You can always leave").
     */
    fun cancel(m: Member, id: UUID): ImportJobView {
        m.requireAdmin()
        tx.run {
            val job = load(id)
            if (job.status in setOf("completed", "cancelled", "syncing")) throw ConflictException("finished", "This import has already finished")
            dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.STATUS, "cancelled").set(IMPORT_JOBS.TOKEN_ENCRYPTED, null as String?)
                .set(IMPORT_JOBS.REFRESH_TOKEN_ENCRYPTED, null as String?)
                .set(IMPORT_JOBS.FINISHED_AT, Instant.now(clock)).where(IMPORT_JOBS.ID.eq(id)).execute()
        }
        return get(m, id)
    }

    /**
     * The cutover: stop syncing changes from Harvest now; the token is forgotten. Ending access
     * only removes access, so it works while the account is read-only ("You can always leave").
     */
    fun stopSync(m: Member, id: UUID): ImportJobView {
        m.requireAdmin()
        tx.run {
            if (load(id).status != "syncing") throw ConflictException("not_syncing", "This import isn't syncing")
        }
        importer.endSync(id, null)
        return get(m, id)
    }

    /** Brings over Harvest's changes now instead of waiting for the next round. */
    fun syncNow(m: Member, id: UUID): ImportJobView {
        m.requireWritable()
        m.requireAdmin()
        tx.run {
            if (load(id).status != "syncing") throw ConflictException("not_syncing", "This import isn't syncing")
        }
        importer.sync(id)
        return get(m, id)
    }

    fun list(m: Member): List<ImportJobView> {
        m.requireAdmin()
        return tx.run { dsl.selectFrom(IMPORT_JOBS).orderBy(IMPORT_JOBS.CREATED_AT.desc()).limit(20).fetch().map(::view) }
    }

    fun get(m: Member, id: UUID): ImportJobView {
        m.requireAdmin()
        return tx.run { view(load(id)) }
    }

    fun issues(m: Member, id: UUID): List<ImportIssueView> {
        m.requireAdmin()
        return tx.run {
            load(id)
            dsl.selectFrom(IMPORT_ISSUES).where(IMPORT_ISSUES.JOB_ID.eq(id)).orderBy(IMPORT_ISSUES.CREATED_AT).limit(1000).fetch()
                .map { ImportIssueView(it.id, it.severity, it.entityType, it.externalId, it.reason, it.createdAt) }
        }
    }

    private fun load(id: UUID): ImportJobsRecord = dsl.selectFrom(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Import")

    private fun view(r: ImportJobsRecord) = ImportJobView(
        id = r.id, source = r.source, mode = r.mode, status = r.status, phase = r.phase,
        progress = json.readTree(r.progress.data()), stats = json.readTree(r.stats.data()), verification = r.verification?.let { json.readTree(it.data()) },
        error = r.error, externalAccountId = r.externalAccountId,
        issueCount = dsl.fetchCount(IMPORT_ISSUES, IMPORT_ISSUES.JOB_ID.eq(r.id).and(IMPORT_ISSUES.SEVERITY.eq("error"))),
        canResume = r.status == "failed" && r.tokenEncrypted != null,
        startedAt = r.startedAt, finishedAt = r.finishedAt, createdAt = r.createdAt,
        syncUntil = r.syncUntil, lastSyncedAt = r.lastSyncedAt,
    )

    private fun enqueue(id: UUID) {
        if (settings.runInline) {
            importer.run(id)
            return
        }
        val client = scheduler.ifAvailable ?: error("The job scheduler is not running")
        client.scheduleIfNotExists(task.getObject().instance(id.toString(), id.toString()), Instant.now(clock))
    }
}

@Configuration
class ImportJobsConfig {
    /** One execution per import job; db-scheduler restarts it if the process dies, and the run resumes from its checkpoint. */
    @Bean
    fun harvestImportTask(importer: HarvestImporter): OneTimeTask<String> =
        Tasks.oneTime("harvest-import", String::class.java).execute { instance, _ -> importer.run(UUID.fromString(instance.data)) }

    /** Imports in their sync window bring over Harvest's changes (spec §6, step 4). */
    @Bean
    fun harvestSyncTask(importer: HarvestImporter, settings: HarvestSettings): com.github.kagkarlsson.scheduler.task.helper.RecurringTask<Void> =
        Tasks.recurring("harvest-sync", com.github.kagkarlsson.scheduler.task.schedule.FixedDelay.of(settings.syncInterval)).execute { _, _ -> importer.syncAll() }
}

@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "imports", description = "Importing from Harvest (admins)")
class ImportController(private val jobs: ImportJobService) {
    @PostMapping("/harvest")
    @ResponseStatus(HttpStatus.CREATED)
    fun startHarvest(@RequestBody body: StartHarvestImport) = jobs.startHarvest(Current.member(), body)

    @GetMapping
    fun list() = jobs.list(Current.member())

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = jobs.get(Current.member(), id)

    @GetMapping("/{id}/issues")
    fun issues(@PathVariable id: UUID) = jobs.issues(Current.member(), id)

    @PostMapping("/{id}/resume")
    fun resume(@PathVariable id: UUID) = jobs.resume(Current.member(), id)

    @PostMapping("/{id}/stop_sync")
    fun stopSync(@PathVariable id: UUID) = jobs.stopSync(Current.member(), id)

    @PostMapping("/{id}/sync")
    fun syncNow(@PathVariable id: UUID) = jobs.syncNow(Current.member(), id)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID) = jobs.cancel(Current.member(), id)
}
