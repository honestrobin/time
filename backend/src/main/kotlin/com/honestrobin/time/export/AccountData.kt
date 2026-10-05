// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.ACCOUNT_EXPORTS
import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.FILES
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.RETIRED_PUBLIC_LINKS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.AccountExportsRecord
import com.honestrobin.time.files.FileStorage
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.TenantAwareTransactionManager
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.RateLimiter
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/** Time between asking to delete an account and deleting it (spec §13). */
val DELETION_GRACE: Duration = Duration.ofDays(14)

@ConfigurationProperties(prefix = "honestrobin.account-data")
data class AccountDataSettings(
    /** How long a finished export can be downloaded. */
    val exportRetention: Duration = Duration.ofDays(7),
    /** Largest export zip accepted for import. */
    val importMaxBytes: Long = 2L * 1024 * 1024 * 1024,
)

data class AccountExportView(
    val id: UUID,
    /** queued, running, ready, failed or expired. */
    val status: String,
    val filename: String?,
    val size: Long?,
    val sha256: String?,
    val error: String?,
    val requestedBy: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val expiresAt: Instant?,
)

/**
 * Full account exports (spec §13): requested by an admin, built in the background, announced by
 * email and downloadable for a week. Works on every plan and edition, also when the account has
 * lapsed or is about to be deleted, because the data belongs to the customer.
 */
@Service
class AccountExportService(
    private val dsl: DSLContext,
    private val tx: Tx,
    transactions: PlatformTransactionManager,
    private val exporter: AccountExporter,
    private val storage: FileStorage,
    private val mailer: Mailer,
    private val settings: AccountDataSettings,
    private val props: HonestRobinProperties,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @Qualifier("accountExportTask") private val task: ObjectProvider<OneTimeTask<String>>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One consistent snapshot for the whole export. */
    private val snapshot = TransactionTemplate(transactions).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    @Transactional
    fun request(m: Member): AccountExportView {
        m.requireAdmin()
        val busy = dsl.fetchExists(ACCOUNT_EXPORTS, ACCOUNT_EXPORTS.STATUS.`in`("queued", "running"))
        if (busy) throw ConflictException("export_running", "An export is already being prepared")
        val r = dsl.newRecord(ACCOUNT_EXPORTS).apply {
            accountId = m.accountId
            requestedBy = m.membershipId
            status = "queued"
            store()
        }
        val id = r.id
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                val client = scheduler.ifAvailable ?: return
                // The scheduler runs on wall-clock time, not on the app's (testable) clock.
                client.scheduleIfNotExists(task.getObject().instance(id.toString(), "${m.accountId}:$id"), Instant.now())
            }
        })
        return views(listOf(r)).single()
    }

    /** Moving out: a new export, or the one already being prepared. */
    @Transactional
    fun requestOrRunning(m: Member): AccountExportView {
        m.requireAdmin()
        val running = dsl.selectFrom(ACCOUNT_EXPORTS).where(ACCOUNT_EXPORTS.STATUS.`in`("queued", "running"))
            .orderBy(ACCOUNT_EXPORTS.CREATED_AT.desc()).limit(1).fetchOne()
        return if (running != null) views(listOf(running)).single() else request(m)
    }

    @Transactional(readOnly = true)
    fun list(m: Member): List<AccountExportView> {
        m.requireAdmin()
        return views(dsl.selectFrom(ACCOUNT_EXPORTS).orderBy(ACCOUNT_EXPORTS.CREATED_AT.desc()).limit(10).fetch())
    }

    @Transactional(readOnly = true)
    fun download(m: Member, id: UUID, response: HttpServletResponse) {
        m.requireAdmin()
        val r = dsl.selectFrom(ACCOUNT_EXPORTS).where(ACCOUNT_EXPORTS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Export")
        if (r.status != "ready" || r.storageKey == null) throw ConflictException("export_not_ready", "This export can't be downloaded (${r.status})")
        response.contentType = "application/zip"
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(r.filename).build().toString())
        r.size?.let { response.setContentLengthLong(it) }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        storage.open(r.storageKey).use { it.copyTo(response.outputStream) }
    }

    /** Builds the export (the background task). */
    fun run(accountId: UUID, exportId: UUID) {
        val claimed = DbContext.forAccount(accountId) {
            tx.run {
                val r = dsl.selectFrom(ACCOUNT_EXPORTS).where(ACCOUNT_EXPORTS.ID.eq(exportId)).forUpdate().fetchOne()
                // A run that died midway (the process stopped) is picked up again after a while.
                val stale = r?.status == "running" && r.startedAt?.isBefore(Instant.now(clock).minus(Duration.ofMinutes(30))) == true
                if (r == null || !(r.status == "queued" || stale)) return@run false
                r.status = "running"
                r.startedAt = Instant.now(clock)
                r.store()
                true
            }
        }
        if (!claimed) return
        val tmp = Files.createTempFile("honestrobin-export", ".zip")
        try {
            val manifest = DbContext.forAccount(accountId) { snapshot.execute { exporter.write(accountId, tmp) }!! }
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(Files.newInputStream(tmp), digest).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
            val key = "exports/$accountId/$exportId.zip"
            storage.putFile(key, tmp, "application/zip")
            val slug = manifest.accountName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "account" }
            DbContext.forAccount(accountId) {
                tx.run {
                    val r = dsl.selectFrom(ACCOUNT_EXPORTS).where(ACCOUNT_EXPORTS.ID.eq(exportId)).fetchOne()!!
                    r.status = "ready"
                    r.storageKey = key
                    r.filename = "honest-robin-$slug-${LocalDate.ofInstant(manifest.exportedAt, java.time.ZoneOffset.UTC)}.zip"
                    r.size = Files.size(tmp)
                    r.sha256 = HexFormat.of().formatHex(digest.digest())
                    r.finishedAt = Instant.now(clock)
                    r.expiresAt = r.finishedAt.plus(settings.exportRetention)
                    r.store()
                    notifyReady(r, manifest.accountName)
                }
            }
        } catch (e: Exception) {
            log.error("Export {} of account {} failed", exportId, accountId, e)
            DbContext.forAccount(accountId) {
                tx.run {
                    dsl.update(ACCOUNT_EXPORTS).set(ACCOUNT_EXPORTS.STATUS, "failed").set(ACCOUNT_EXPORTS.ERROR, e.message?.take(500) ?: e.javaClass.simpleName)
                        .set(ACCOUNT_EXPORTS.FINISHED_AT, Instant.now(clock)).where(ACCOUNT_EXPORTS.ID.eq(exportId)).execute()
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun notifyReady(r: AccountExportsRecord, accountName: String) {
        val to = r.requestedBy?.let { dsl.select(MEMBERSHIPS.EMAIL, MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(it)).fetchOne() } ?: return
        val locale = Locale.forLanguageTag(dsl.select(ACCOUNTS.LOCALE).from(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!.value1())
        mailer.send(
            "export-ready", to.value1(), locale,
            mapOf("name" to to.value2(), "account" to accountName, "link" to "${props.baseUrl}/settings/account#export", "expires" to LocalDate.ofInstant(r.expiresAt, java.time.ZoneOffset.UTC)),
            arrayOf(accountName),
        )
    }

    /** Removes expired export files; the rows stay as a record. */
    fun expireDue() {
        val due = tx.system {
            dsl.selectFrom(ACCOUNT_EXPORTS).where(ACCOUNT_EXPORTS.STATUS.eq("ready")).and(ACCOUNT_EXPORTS.EXPIRES_AT.lt(Instant.now(clock))).fetch()
        }
        for (r in due) {
            r.storageKey?.let { runCatching { storage.delete(it) } }
            tx.system {
                dsl.update(ACCOUNT_EXPORTS).set(ACCOUNT_EXPORTS.STATUS, "expired").setNull(ACCOUNT_EXPORTS.STORAGE_KEY).where(ACCOUNT_EXPORTS.ID.eq(r.id)).execute()
            }
        }
    }

    private fun views(rows: List<AccountExportsRecord>): List<AccountExportView> {
        val names = dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.NAME).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.`in`(rows.mapNotNull { it.requestedBy })).fetchMap(MEMBERSHIPS.ID, MEMBERSHIPS.NAME)
        return rows.map { AccountExportView(it.id, it.status, it.filename, it.size, it.sha256, it.error, it.requestedBy?.let(names::get), it.createdAt, it.finishedAt, it.expiresAt) }
    }
}

/**
 * Published just before an account is deleted for good, while its rows still exist, so each
 * module can end what the account holds outside: connections at Stripe, QuickBooks, Xero and
 * Storecove, and a Honest Robin Cloud subscription. Listeners log their failures; the deletion
 * goes ahead.
 */
data class AccountPurging(val accountId: UUID)

data class DeletionRequest(
    /** The account's name, typed out, so nobody deletes an account by accident. */
    val confirmName: String = "",
)

/**
 * Deleting an account (spec §13): an admin asks, the account turns read-only for a grace period
 * (exports still work), every admin is told, and after the grace period the account and
 * everything in it are deleted for good. Until then any admin can cancel.
 */
@Service
class AccountDeletionService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val storage: FileStorage,
    private val mailer: Mailer,
    private val settings: AccountDataSettings,
    private val props: HonestRobinProperties,
    private val clock: Clock,
    private val events: org.springframework.context.ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun request(m: Member, input: DeletionRequest) {
        m.requireAdmin()
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!!
        if (account.status == "pending_deletion") throw ConflictException("deletion_pending", "This account is already scheduled for deletion")
        if (input.confirmName.trim() != account.name.trim()) throw ValidationException("confirm_name", "Type the account's name exactly as it is shown")
        account.status = "pending_deletion"
        account.deletionRequestedAt = Instant.now(clock)
        account.store()
        val deletesOn = LocalDate.ofInstant(account.deletionRequestedAt.plus(DELETION_GRACE), java.time.ZoneId.of(account.timezone))
        notifyAdmins(m.accountId, "account-deletion", mapOf("account" to account.name, "by" to m.name, "date" to deletesOn, "link" to "${props.baseUrl}/settings/account#delete"), arrayOf(account.name, deletesOn))
    }

    @Transactional
    fun cancel(m: Member) {
        m.requireAdmin()
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!!
        if (account.status != "pending_deletion") throw ConflictException("no_deletion", "This account isn't scheduled for deletion")
        account.status = if (account.lapsedAt != null) "lapsed" else "active"
        account.deletionRequestedAt = null
        account.store()
        notifyAdmins(m.accountId, "account-deletion-cancelled", mapOf("account" to account.name, "by" to m.name, "link" to props.baseUrl), arrayOf(account.name))
    }

    /** Deletes every account whose grace period has passed (the daily job). */
    fun purgeDue(): Int {
        val due = tx.system {
            dsl.select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.STATUS.eq("pending_deletion"))
                .and(ACCOUNTS.DELETION_REQUESTED_AT.le(Instant.now(clock).minus(DELETION_GRACE))).fetch(ACCOUNTS.ID)
        }
        due.forEach { id ->
            try {
                purge(id)
            } catch (e: Exception) {
                log.error("Deleting account {} failed; will try again", id, e)
            }
        }
        return due.size
    }

    /** Hard delete: rows, files, exports and the audit log, plus users left without any account. */
    fun purge(accountId: UUID) {
        events.publishEvent(AccountPurging(accountId))
        val blobs = mutableListOf<String>()
        tx.system {
            // The invoices' public links stay dead: no import can bring them back (V22).
            dsl.insertInto(RETIRED_PUBLIC_LINKS, RETIRED_PUBLIC_LINKS.TOKEN_SHA256)
                .select(DSL.select(DSL.field("sha256(convert_to({0}, 'UTF8'))", ByteArray::class.java, INVOICES.PUBLIC_TOKEN)).from(INVOICES)
                    .where(INVOICES.ACCOUNT_ID.eq(accountId)).and(INVOICES.PUBLIC_TOKEN.isNotNull))
                .onConflictDoNothing().execute()
            blobs += dsl.select(FILES.STORAGE_KEY).from(FILES).where(FILES.ACCOUNT_ID.eq(accountId)).fetch(FILES.STORAGE_KEY)
            blobs += dsl.select(ACCOUNT_EXPORTS.STORAGE_KEY).from(ACCOUNT_EXPORTS).where(ACCOUNT_EXPORTS.ACCOUNT_ID.eq(accountId)).and(ACCOUNT_EXPORTS.STORAGE_KEY.isNotNull).fetch(ACCOUNT_EXPORTS.STORAGE_KEY)
            val people = dsl.select(MEMBERSHIPS.USER_ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ACCOUNT_ID.eq(accountId)).and(MEMBERSHIPS.USER_ID.isNotNull).fetch(MEMBERSHIPS.USER_ID)
            // Otherwise every cascaded row would leave a fresh audit entry behind.
            TenantAwareTransactionManager.setLocal("honestrobin.audit_disabled", "on")
            dsl.select(DSL.field("honestrobin_purge_audit({0})", Long::class.java, DSL.value(accountId))).fetchOne()
            dsl.deleteFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).execute()
            // People who were only here have no reason to keep a sign-in on this instance, nor their
            // sign-up and sign-in history in the audit log.
            val gone = dsl.deleteFrom(USERS).where(USERS.ID.`in`(people)).and(USERS.IS_INSTANCE_ADMIN.isFalse)
                .andNotExists(DSL.selectOne().from(MEMBERSHIPS).where(MEMBERSHIPS.USER_ID.eq(USERS.ID)))
                .returning(USERS.ID).fetch(USERS.ID)
            if (gone.isNotEmpty()) {
                dsl.select(DSL.field("honestrobin_purge_user_audit({0})", Long::class.java, DSL.value(gone.toTypedArray()))).fetchOne()
            }
            // The deletion itself is the one thing kept: who, when, nothing else.
            dsl.insertInto(AUDIT_LOG).set(AUDIT_LOG.ACTION, "accounts.purged").set(AUDIT_LOG.ENTITY_TYPE, "accounts").set(AUDIT_LOG.ENTITY_ID, accountId).execute()
        }
        blobs.forEach { key -> runCatching { storage.delete(key) }.onFailure { log.warn("Could not delete blob {}: {}", key, it.message) } }
        log.info("Account {} deleted for good", accountId)
    }

    private fun notifyAdmins(accountId: UUID, template: String, model: Map<String, Any?>, subject: Array<Any?>) {
        val locale = Locale.forLanguageTag(dsl.select(ACCOUNTS.LOCALE).from(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).fetchOne()!!.value1())
        dsl.select(MEMBERSHIPS.EMAIL, MEMBERSHIPS.NAME).from(MEMBERSHIPS)
            .where(MEMBERSHIPS.ROLE.eq("admin")).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
            .fetch().forEach { mailer.send(template, it.value1(), locale, model + ("name" to it.value2()), subject) }
    }
}

@Configuration
class AccountDataJobsConfig {
    @Bean
    fun accountExportTask(@org.springframework.context.annotation.Lazy exports: AccountExportService): OneTimeTask<String> =
        Tasks.oneTime("account-export", String::class.java).execute { instance, _ ->
            val (account, export) = instance.data.split(':').map(UUID::fromString)
            exports.run(account, export)
        }

    /** Expired export files and accounts past their grace period. */
    @Bean
    fun accountLifecycleTask(@org.springframework.context.annotation.Lazy exports: AccountExportService, @org.springframework.context.annotation.Lazy deletions: AccountDeletionService): RecurringTask<Void> =
        Tasks.recurring("account-lifecycle", FixedDelay.of(Duration.ofHours(1))).execute { _, _ ->
            exports.expireDue()
            deletions.purgeDue()
        }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "account", description = "Exporting, importing and deleting accounts")
class AccountDataController(
    private val exports: AccountExportService,
    private val connections: ConnectionsService,
    private val deletions: AccountDeletionService,
    private val importer: AccountImporter,
    private val settings: AccountDataSettings,
    private val props: HonestRobinProperties,
    private val dsl: DSLContext,
    private val tx: Tx,
    private val recentAuth: RecentAuth,
    private val limiter: RateLimiter,
) {
    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start a full export of the account (admins). An email follows when it's ready.")
    fun requestExport(): AccountExportView {
        recentAuth.requireUnlessApiToken()
        return exports.request(Current.member())
    }

    @GetMapping("/exports")
    fun listExports(): List<AccountExportView> = exports.list(Current.member())

    /**
     * Moving out, in one step: starts an export of everything (or returns the one being prepared)
     * and lists what's still connected. It changes nothing else: cancelling and deleting are
     * separate steps. Works on every plan and in every state of an account.
     */
    @PostMapping("/account/move_out")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Move out (admins): export everything and list what's still connected. Changes nothing else.")
    fun moveOut(): MoveOutView {
        recentAuth.requireUnlessApiToken()
        val m = Current.member()
        return MoveOutView(exports.requestOrRunning(m), connections.list(m))
    }

    @GetMapping("/account/connections")
    @Operation(summary = "Everything still connected to the account, and where each is ended (admins)")
    fun listConnections(): List<ConnectionView> = connections.list(Current.member())

    @GetMapping("/exports/{id}/download")
    @Operation(summary = "Download a finished export as a zip")
    fun download(@PathVariable id: UUID, response: HttpServletResponse) = exports.download(Current.member(), id, response)

    @PostMapping("/account/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Schedule the account for deletion after the grace period (admins)")
    fun requestDeletion(@RequestBody body: DeletionRequest) {
        recentAuth.require()
        deletions.request(Current.member(), body)
    }

    @DeleteMapping("/account/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel a scheduled deletion (admins)")
    fun cancelDeletion() = deletions.cancel(Current.member())

    /**
     * Creates an account on this instance from an export zip (send the zip as the request body,
     * Content-Type application/zip). The person importing becomes an admin of it.
     */
    @PostMapping("/accounts/import", consumes = ["application/zip", "application/octet-stream"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import an account from an export zip")
    fun importAccount(request: HttpServletRequest): ImportedAccount {
        val userId = Current.principal().userId
        val instanceAdmin = tx.system { dsl.select(USERS.IS_INSTANCE_ADMIN).from(USERS).where(USERS.ID.eq(userId)).fetchOne()?.value1() } ?: false
        if (props.signupMode != HonestRobinProperties.SignupMode.OPEN && !instanceAdmin) {
            throw ApiException(HttpStatus.FORBIDDEN, "signup_closed", "Only the instance admin can create accounts on this instance")
        }
        // One import at a time per person (the importer also locks across servers), and a few a
        // day: each is a big transaction.
        if (!importing.add(userId)) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "import_running", "An import is already running")
        if (!limiter.tryAcquire("import:$userId", MAX_IMPORTS_PER_DAY, Duration.ofDays(1))) {
            importing.remove(userId)
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "import_limit", "You have imported $MAX_IMPORTS_PER_DAY exports today. Try again tomorrow.")
        }
        val tmp = Files.createTempFile("honestrobin-import", ".zip")
        try {
            request.inputStream.use { input ->
                Files.newOutputStream(tmp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > settings.importMaxBytes) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "The export is larger than this instance accepts")
                        out.write(buffer, 0, n)
                    }
                }
            }
            return importer.import(tmp, userId)
        } catch (e: java.util.zip.ZipException) {
            throw com.honestrobin.time.platform.web.BadRequestException("not_an_export", "This file isn't a zip file")
        } finally {
            Files.deleteIfExists(tmp)
            importing.remove(userId)
        }
    }

    /** Uploads in progress on this server; the importer's lock covers the database work on every server. */
    private val importing: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    companion object {
        const val MAX_IMPORTS_PER_DAY = 10
    }
}
