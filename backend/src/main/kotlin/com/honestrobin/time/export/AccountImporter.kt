// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.FILES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.UsersRecord
import com.honestrobin.time.files.FileStorage
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.TenantAwareTransactionManager
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.BadRequestException
import com.honestrobin.time.platform.web.ConflictException
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.TableRecord
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipFile

data class ImportedAccount(val accountId: UUID, val name: String, val rows: Map<String, Long>, val files: Int)

/**
 * Recreates an account from its export zip (spec §13), on this instance, in one transaction: it
 * all goes in, or none of it does. Row ids are kept, so links between rows stay as they were.
 * People are matched to existing users by email; anyone new gets a user without a password, who
 * signs in with a sign-in link.
 */
@Component
class AccountImporter(
    private val dsl: DSLContext,
    private val storage: FileStorage,
    private val tx: TransactionTemplate,
) {
    // Rows are read as trees, with decimals exactly as written: 1.0 stays 1.0, also inside jsonb
    // values.
    private val json: ObjectMapper = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .build()

    /**
     * Reading budget for one zip: a crafted zip can claim small entries and inflate to terabytes,
     * so every byte read out of it is counted, against a limit scaled to the zip's own size.
     */
    private class Budget(zipSize: Long) {
        var remaining: Long = (zipSize * 30).coerceIn(256L shl 20, 20L shl 30)

        fun wrap(input: java.io.InputStream): java.io.InputStream = object : java.io.FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) take(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) take(it.toLong()) }
        }

        private fun take(n: Long) {
            remaining -= n
            if (remaining < 0) throw BadRequestException("export_too_large", "This export unpacks to far more than its size suggests, so it isn't imported")
        }
    }

    // One import runs in one thread; the component is shared.
    private val budgets = ThreadLocal<Budget>()
    private val budget: Budget get() = budgets.get()

    fun import(zipPath: Path, importingUserId: UUID): ImportedAccount = try {
        budgets.set(Budget(java.nio.file.Files.size(zipPath)))
        importZip(zipPath, importingUserId)
    } finally {
        budgets.remove()
    }

    private fun importZip(zipPath: Path, importingUserId: UUID): ImportedAccount = ZipFile(zipPath.toFile()).use { zip ->
        if (zip.size() > MAX_ENTRIES) throw BadRequestException("export_too_large", "This export has more files in it than any export Honest Robin makes")
        val manifest = zip.getEntry("manifest.json")?.let { budget.wrap(zip.getInputStream(it)).use { s -> json.readTree(s) } }
            ?: throw BadRequestException("not_an_export", "This file isn't a Honest Robin export: it has no manifest.json")
        if (manifest["format"]?.asText() != ExportFormat.FORMAT) throw BadRequestException("not_an_export", "This file isn't a Honest Robin export")
        val version = manifest["version"]?.asInt() ?: 0
        if (version > ExportFormat.VERSION) {
            throw BadRequestException("export_too_new", "This export was made by a newer version of Honest Robin. Update this instance, then import it.")
        }
        // Every data file must be exactly what was exported.
        val expected = manifest["tables"].associate { it["name"].asText() to it["sha256"].asText() }
        for (t in ExportFormat.TABLES) {
            val entry = zip.getEntry("data/${t.name}.json") ?: throw BadRequestException("export_damaged", "The export is missing data/${t.name}.json")
            val digest = MessageDigest.getInstance("SHA-256")
            budget.wrap(zip.getInputStream(entry)).use { s -> s.copyTo(java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) }
            if (expected[t.name] != HexFormat.of().formatHex(digest.digest())) {
                throw BadRequestException("export_damaged", "data/${t.name}.json doesn't match its checksum; the file was changed or damaged")
            }
        }
        val accountId = UUID.fromString(manifest["account_id"].asText())

        DbContext.system {
            tx.execute {
                // One import per person at a time, across all servers of the instance.
                val locked = dsl.fetchValue("select pg_try_advisory_xact_lock(hashtextextended(?, 7234003))", "import:$importingUserId") as Boolean
                if (!locked) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "import_running", "An import is already running")
                // The rows carry their own history; importing them shouldn't add a second one.
                TenantAwareTransactionManager.setLocal("honestrobin.audit_disabled", "on")
                if (dsl.fetchExists(ACCOUNTS, ACCOUNTS.ID.eq(accountId))) {
                    throw ConflictException("account_exists", "This account is already on this instance")
                }
                val importer = dsl.selectFrom(USERS).where(USERS.ID.eq(importingUserId)).fetchOne()
                    ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in again")
                val people = People(readUsers(zip), importer)
                val counts = linkedMapOf<String, Long>()
                for (t in ExportFormat.TABLES.filter { it.table != USERS }) {
                    counts[t.name] = try {
                        insertRows(zip, t) { row -> prepare(t, row, accountId, people) }
                    } catch (e: RuntimeException) {
                        // Keys between an account's tables include account_id (V15), so the database
                        // itself refuses a row that points at another account's data.
                        if (sqlState(e) == "23503") throw BadRequestException("export_damaged", "data/${t.name}.json refers to data that isn't part of this account")
                        throw e
                    }
                }
                val files = restoreFiles(zip, accountId)
                addImporter(accountId, importer)
                TenantAwareTransactionManager.setLocal("honestrobin.audit_disabled", "off")
                dsl.insertInto(AUDIT_LOG)
                    .set(AUDIT_LOG.ACCOUNT_ID, accountId).set(AUDIT_LOG.ACTOR_USER_ID, importingUserId).set(AUDIT_LOG.ACTOR_TYPE, "user")
                    .set(AUDIT_LOG.ACTION, "accounts.imported").set(AUDIT_LOG.ENTITY_TYPE, "accounts").set(AUDIT_LOG.ENTITY_ID, accountId)
                    .set(AUDIT_LOG.DIFF, org.jooq.JSONB.valueOf(json.writeValueAsString(mapOf("exported_at" to manifest["exported_at"]?.asText(), "from_version" to manifest["app_version"]?.asText()))))
                    .execute()
                ImportedAccount(accountId, manifest["account_name"].asText(), counts, files)
            }!!
        }
    }

    /** The exported people: old user id → email. Nobody gets a sign-in here except the person importing. */
    private class People(val emails: Map<UUID, String>, val importer: UsersRecord) {
        val importerOldIds: Set<UUID> = emails.filterValues { it.equals(importer.email, ignoreCase = true) }.keys
    }

    private fun readUsers(zip: ZipFile): Map<UUID, String> {
        val map = mutableMapOf<UUID, String>()
        insertRows(zip, ExportFormat.table(USERS.name)!!) { row ->
            map[UUID.fromString(row["id"].asText())] = row["email"].asText()
            null
        }
        return map
    }

    /**
     * Checks and adjusts a row before it is written. Every row must belong to the account being
     * imported: the zip is uploaded by a user and written with row-level security off, so a row
     * naming another account would land in it.
     */
    private fun prepare(t: ExportTable, row: ObjectNode, accountId: UUID, people: People): ObjectNode {
        val owner = if (t.table == ACCOUNTS) row["id"] else row["account_id"]
        if (owner?.asText() != accountId.toString()) {
            throw BadRequestException("export_damaged", "data/${t.name}.json has rows of another account")
        }
        when (t.table) {
            // People keep their place in the account, but only the person importing is linked to
            // a sign-in here; the others are invited again, so nobody is added to an account
            // without saying yes.
            MEMBERSHIPS -> {
                val user = row["user_id"]?.takeIf { !it.isNull }?.asText()?.let(UUID::fromString)
                if (user != null && user in people.importerOldIds) {
                    row.put("user_id", people.importer.id.toString())
                } else {
                    row.putNull("user_id")
                    if (row["status"]?.asText() in setOf("active", "invited")) row.put("status", "pending_invite")
                }
            }
            AUDIT_LOG -> {
                val actor = row["actor_user_id"]?.takeIf { !it.isNull }?.asText()?.let(UUID::fromString)
                if (actor != null) {
                    if (actor in people.importerOldIds) row.put("actor_user_id", people.importer.id.toString()) else row.putNull("actor_user_id")
                }
            }
            // Storage keys come from this instance, never from the zip.
            FILES -> row.put("storage_key", "$accountId/${UUID.randomUUID()}")
        }
        return row
    }

    private fun sqlState(e: Throwable): String? =
        generateSequence(e) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState

    /** Streams a table's rows into the database in batches; [prepare] may change a row or skip it (null). */
    private fun insertRows(zip: ZipFile, t: ExportTable, prepare: (ObjectNode) -> ObjectNode?): Long {
        val entry = zip.getEntry("data/${t.name}.json")
        var count = 0L
        val batch = mutableListOf<TableRecord<*>>()
        fun flush() {
            if (batch.isEmpty()) return
            dsl.batchInsert(batch).execute()
            batch.clear()
        }
        budget.wrap(zip.getInputStream(entry)).use { input ->
            json.createParser(input).use { p ->
                if (p.nextToken() != JsonToken.START_ARRAY) throw BadRequestException("export_damaged", "data/${t.name}.json is not a list of rows")
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    val row = prepare((json.readTree(p) as ObjectNode)) ?: continue
                    batch += record(t, row)
                    count++
                    if (batch.size >= 500) flush()
                }
            }
        }
        flush()
        return count
    }

    @Suppress("UNCHECKED_CAST")
    private fun record(t: ExportTable, row: ObjectNode): TableRecord<*> {
        val r = dsl.newRecord(t.table) as TableRecord<*>
        row.propertyNames().forEach { name ->
            val field = t.table.field(name) as Field<Any?>?
                ?: throw BadRequestException("export_damaged", "data/${t.name}.json has an unknown column: $name")
            if (name in t.omit) return@forEach
            r.set(field, ExportCodec.read(row[name], field.type, json))
        }
        return r
    }

    /** Puts the receipts and other files back into storage; removes them again if the import fails. */
    private fun restoreFiles(zip: ZipFile, accountId: UUID): Int {
        val written = mutableListOf<String>()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) written.forEach { runCatching { storage.delete(it) } }
            }
        })
        val files = dsl.selectFrom(FILES).where(FILES.ACCOUNT_ID.eq(accountId)).fetch()
        val paths = zip.entries().asSequence().filter { it.name.startsWith("files/") && !it.isDirectory }
            .associateBy { it.name.removePrefix("files/").substringBefore('/') }
        for (f in files) {
            val entry = paths[f.id.toString()] ?: continue
            val tmp = kotlin.io.path.createTempFile("import", ".bin")
            try {
                budget.wrap(zip.getInputStream(entry)).use { input -> java.nio.file.Files.copy(input, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                // The type comes from the bytes, not from the zip.
                val head = java.nio.file.Files.newInputStream(tmp).use { it.readNBytes(32) }
                f.mime = com.honestrobin.time.files.FileTypes.detect(head)
                f.size = java.nio.file.Files.size(tmp)
                f.store()
                storage.putFile(f.storageKey, tmp, f.mime)
                written += f.storageKey
            } finally {
                java.nio.file.Files.deleteIfExists(tmp)
            }
        }
        return written.size
    }

    /** The person importing becomes an active admin of the account, matched by user or by email. */
    private fun addImporter(accountId: UUID, user: UsersRecord) {
        val existing = dsl.selectFrom(MEMBERSHIPS).where(MEMBERSHIPS.ACCOUNT_ID.eq(accountId))
            .and(MEMBERSHIPS.USER_ID.eq(user.id).or(DSL.lower(MEMBERSHIPS.EMAIL).eq(user.email.lowercase()))).fetchOne()
        if (existing == null) {
            dsl.insertInto(MEMBERSHIPS)
                .set(MEMBERSHIPS.ACCOUNT_ID, accountId).set(MEMBERSHIPS.USER_ID, user.id).set(MEMBERSHIPS.NAME, user.name)
                .set(MEMBERSHIPS.EMAIL, user.email).set(MEMBERSHIPS.ROLE, "admin").set(MEMBERSHIPS.STATUS, "active")
                .execute()
        } else {
            existing.userId = user.id
            existing.role = "admin"
            existing.status = "active"
            existing.isActive = true
            existing.store()
        }
    }

    companion object {
        /** Far above any real export (one file per receipt plus ~30 data files). */
        const val MAX_ENTRIES = 300_000
    }
}
