// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import tools.jackson.databind.JsonNode
import com.honestrobin.time.db.Public
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Spec §13 and AT-6.1, AT-6.3: full export, import into a fresh account, deletion. */
class AccountDataTest : IntegrationTest() {
    @Autowired lateinit var deletions: AccountDeletionService

    private class Seeded(val admin: TestClient, val member: TestClient, val expense: UUID, val invoiceNumber: String, val receipt: ByteArray)

    private fun seed(): Seeded {
        val admin = signup(accountName = "Fjord & Pine Studio", email = uniqueEmail("owner"))
        membershipId(admin)
        val member = invite(admin, extra = mapOf("default_billable_rate" to 9_000, "cost_rate" to 4_000))
        val task = createTask(admin, "Design")
        val client = createClient(admin, "Kiwi Outdoor Ltd", "NZD")
        admin.post("/api/v1/clients/$client/contacts", mapOf("name" to "Aroha Ngata", "email" to "aroha@kiwi.test", "is_invoice_recipient" to true)).expect(201)
        val project = createProject(admin, client, listOf(task), listOf(member), mapOf("bill_by" to "project", "hourly_rate" to 16_000, "budget_by" to "project", "budget_seconds" to 36_000)).id()
        admin.post("/api/v1/teams", mapOf("name" to "Design", "membership_ids" to listOf(member.membershipId))).expect(201)
        member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-14", "duration_seconds" to 5400, "notes" to "Map legend, \"v2\"")).expect(201)
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 3600)).expect(201)
        val travel = admin.post("/api/v1/expense_categories", mapOf("name" to "Travel")).expect(201).id()
        val expense = member.post("/api/v1/expenses", mapOf("project_id" to project, "category_id" to travel, "spent_date" to "2026-09-15", "amount" to 4_250, "notes" to "Ferry")).expect(201).id()
        val receipt = "%PDF-1.4 ferry receipt ${UUID.randomUUID()}".toByteArray()
        member.upload("/api/v1/expenses/$expense/receipt", "file", "ferry.pdf", "application/pdf", receipt).expect(200)
        val invoice = admin.post("/api/v1/invoices", mapOf("client_id" to client, "from_time" to mapOf("from" to "2026-09-01", "to" to "2026-09-30", "include_expenses" to true))).expect(201).id()
        val number = admin.post("/api/v1/invoices/$invoice/mark_sent").expect(200)["number"].asText()
        admin.post("/api/v1/invoices/$invoice/payments", mapOf("amount" to 10_000, "paid_on" to "2026-09-30")).expect(201)
        return Seeded(admin, member, expense, number, receipt)
    }

    /** Asks for an export and waits for the background job to finish it. */
    private fun export(admin: TestClient): ByteArray {
        val id = admin.post("/api/v1/exports").expect(202).id()
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val e = admin.get("/api/v1/exports").expect(200).body.first { it["id"].asText() == id.toString() }
            if (e["status"].asText() == "ready") break
            assertThat(e["status"].asText()).withFailMessage { "Export failed: $e" }.isIn("queued", "running")
            check(System.currentTimeMillis() < deadline) { "Export did not finish: $e" }
            Thread.sleep(100)
        }
        val res = admin.get("/api/v1/exports/$id/download").expect(200)
        assertThat(res.headers["Content-Disposition"]!!.single()).contains("honest-robin-fjord-pine-studio-")
        return res.bytes
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val file = kotlin.io.path.createTempFile("export", ".zip").toFile().apply { deleteOnExit(); writeBytes(bytes) }
        return ZipFile(file).use { z -> z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes() } }
    }

    private fun sha256(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    @Test
    fun `the export holds every table with checksums, spreadsheets, invoice PDFs and receipts`() {
        val s = seed()
        s.member.post("/api/v1/exports").expectError(403, "forbidden")
        val parts = unzip(export(s.admin))
        val manifest = mapper.readTree(parts["manifest.json"])
        assertThat(manifest["format"].asText()).isEqualTo("honestrobin-export")
        assertThat(manifest["version"].asInt()).isEqualTo(1)
        assertThat(manifest["account_name"].asText()).isEqualTo("Fjord & Pine Studio")
        val tables = manifest["tables"].associateBy { it["name"].asText() }
        assertThat(tables.keys).containsExactlyElementsOf(ExportFormat.TABLES.map { it.name })
        for ((name, t) in tables) {
            val data = parts["data/$name.json"]!!
            assertThat(sha256(data)).withFailMessage { "checksum of $name" }.isEqualTo(t["sha256"].asText())
            assertThat(mapper.readTree(data).size().toLong()).isEqualTo(t["rows"].asLong())
        }
        assertThat(tables["time_entries"]!!["rows"].asInt()).isEqualTo(2)
        assertThat(tables["memberships"]!!["rows"].asInt()).isEqualTo(2)

        // Nothing that signs anyone in leaves the instance.
        val users = parts["data/users.json"]!!.toString(Charsets.UTF_8)
        assertThat(users).doesNotContain("password_hash").doesNotContain("is_instance_admin").contains(s.admin.email)
        assertThat(parts.keys).noneMatch { it.contains("api_tokens") || it.contains("integrations") || it.contains("sessions") }
        // Row per line, so exports diff well.
        assertThat(parts["data/time_entries.json"]!!.toString(Charsets.UTF_8).lines().first()).isEqualTo("[")

        assertThat(parts["csv/time-entries.csv"]!!.toString(Charsets.UTF_8)).contains("Map legend").contains("Kiwi Outdoor Ltd")
        assertThat(parts["csv/expenses.csv"]!!.toString(Charsets.UTF_8)).contains("Ferry")
        assertThat(parts["csv/invoices.csv"]!!.toString(Charsets.UTF_8)).contains(s.invoiceNumber)
        assertThat(parts["csv/contacts.csv"]!!.toString(Charsets.UTF_8)).contains("aroha@kiwi.test")
        assertThat(parts["invoices/invoice-${s.invoiceNumber}.pdf"]!!.copyOf(4).toString(Charsets.US_ASCII)).isEqualTo("%PDF")
        val receipt = manifest["files"].single()
        assertThat(parts[receipt["path"].asText()]).isEqualTo(s.receipt)
        assertThat(receipt["sha256"].asText()).isEqualTo(sha256(s.receipt))
        assertThat(parts["README.txt"]!!.toString(Charsets.UTF_8)).contains("Import an export").contains("API tokens are secrets")
        assertThat(mail.lastTo(s.admin.email!!).subject).isEqualTo("Your export of Fjord & Pine Studio is ready")
    }

    @Test
    fun `every table is either exported or left out on purpose`() {
        val covered = (ExportFormat.TABLES.map { it.table.name } + ExportFormat.EXCLUDED.keys.map { it.name }).toSet()
        val all = Public.PUBLIC.tables.map { it.name }.filter { it != "flyway_schema_history" && it != "scheduled_tasks" }
        assertThat(all.filter { it !in covered }).withFailMessage { "Decide whether these belong in the export: ${all.filter { it !in covered }}" }.isEmpty()
    }

    @Test
    fun `an export imports back after the account is deleted, identical table by table (AT-6_1)`() {
        val s = seed()
        val accountId = s.admin.accountId!!
        val first = export(s.admin)

        // Delete: confirm by name; the account turns read-only, but exports still work.
        s.member.post("/api/v1/account/deletion", mapOf("confirm_name" to "Fjord & Pine Studio")).expectError(403, "forbidden")
        s.admin.post("/api/v1/account/deletion", mapOf("confirm_name" to "Fjord and Pine")).expectError(422, "validation_failed")
        s.admin.post("/api/v1/account/deletion", mapOf("confirm_name" to "Fjord & Pine Studio")).expect(204)
        assertThat(mail.lastTo(s.admin.email!!).subject).startsWith("Fjord & Pine Studio will be deleted on")
        val account = s.admin.get("/api/v1/account").expect(200)
        assertThat(account["status"].asText()).isEqualTo("pending_deletion")
        assertThat(Instant.parse(account["deletes_at"].asText())).isAfter(Instant.now(clock).plus(Duration.ofDays(13)))
        s.member.post("/api/v1/time_entries", mapOf("project_id" to UUID.randomUUID(), "task_id" to UUID.randomUUID(), "spent_date" to "2026-09-16", "duration_seconds" to 60)).let {
            assertThat(it.status).isEqualTo(402)
        }
        // Cancelling and asking again works too.
        s.admin.delete("/api/v1/account/deletion").expect(204)
        assertThat(s.admin.get("/api/v1/account")["status"].asText()).isEqualTo("active")
        s.admin.post("/api/v1/account/deletion", mapOf("confirm_name" to "Fjord & Pine Studio")).expect(204)

        // Not yet: the grace period hasn't passed.
        assertThat(deletions.purgeDue()).isEqualTo(0)
        val previous = clock.travelTo(Instant.now(clock).plus(Duration.ofDays(15)))
        try {
            assertThat(deletions.purgeDue()).isEqualTo(1)
        } finally {
            clock.restore(previous)
        }
        tx.system {
            assertThat(dsl.fetchExists(ACCOUNTS, ACCOUNTS.ID.eq(accountId))).isFalse()
            assertThat(dsl.selectFrom(AUDIT_LOG).where(AUDIT_LOG.ACCOUNT_ID.eq(accountId)).fetch()).isEmpty()
            assertThat(dsl.fetchExists(AUDIT_LOG, AUDIT_LOG.ACTION.eq("accounts.purged").and(AUDIT_LOG.ENTITY_ID.eq(accountId)))).isTrue()
            // People who were only here go, and so does their own sign-up and sign-in history, which
            // has no account id (security review, 4 October 2026).
            assertThat(dsl.fetchExists(USERS, USERS.ID.eq(s.member.userId))).isFalse()
            assertThat(dsl.fetchCount(AUDIT_LOG, AUDIT_LOG.ENTITY_TYPE.eq("users").and(AUDIT_LOG.ENTITY_ID.eq(s.member.userId)))).isZero()
        }
        assertThat(s.admin.get("/api/v1/account").status).isIn(401, 403, 404)

        // A new person imports the zip on this instance. The account was deleted here, so it comes
        // back under a new id: things outside that still name the old one (a subscription that
        // wasn't cancelled) mustn't find it (security review, 4 October 2026).
        val importer = signup(name = "Rangi Importer", accountName = "Scratch")
        val imported = importer.request(HttpMethod.POST, "/api/v1/accounts/import", first, headers = mapOf("Content-Type" to "application/zip")).expect(201)
        val newId = UUID.fromString(imported["account_id"].asText())
        assertThat(newId).isNotEqualTo(accountId)
        assertThat(imported["rows"]["time_entries"].asInt()).isEqualTo(2)
        assertThat(imported["files"].asInt()).isEqualTo(1)

        // The importer is an admin of the imported account and sees its data, receipts included.
        importer.accountId = newId
        assertThat(importer.get("/api/v1/me")["role"].asText()).isEqualTo("admin")
        assertThat(importer.get("/api/v1/time_entries", mapOf("from" to "2026-09-01", "to" to "2026-09-30"))["data"]).hasSize(2)
        assertThat(importer.get("/api/v1/expenses/${s.expense}/receipt").expect(200).bytes).isEqualTo(s.receipt)

        // Nobody but the importer is linked to a sign-in: the others are invited again.
        assertThat(s.member.get("/api/v1/me")["accounts"].values().map { it["id"].asText() }).doesNotContain(accountId.toString())
        val people = importer.get("/api/v1/people").expect(200)["data"]
        assertThat(people.first { it["email"].asText() == s.member.email }["status"].asText()).isEqualTo("pending_invite")

        // Export again and compare: every table is the same under the new account id, except for
        // people's sign-ins (memberships, users, audit actors), the import's own audit entry, and
        // the invoices' public links.
        val again = unzip(export(importer))
        val before = unzip(first)
        fun without(rows: List<JsonNode>, vararg columns: String) = rows.map { (it.deepCopy() as tools.jackson.databind.node.ObjectNode).apply { remove(columns.toList()) } }
        fun rows(parts: Map<String, ByteArray>, name: String): List<JsonNode> = mapper.readTree(parts["data/$name.json"]).toList()
        // The rows as exported, moved to the new id the way the import moves them.
        fun moved(name: String): List<JsonNode> = rows(before, name).map { row ->
            (row.deepCopy() as tools.jackson.databind.node.ObjectNode).apply {
                val owner = if (name == "accounts") "id" else "account_id"
                if (get(owner)?.asText() == accountId.toString()) put(owner, newId.toString())
                if (name == "audit_log" && get("entity_type")?.asText() == "accounts" && get("entity_id")?.asText() == accountId.toString()) put("entity_id", newId.toString())
            }
        }
        val tables = mapper.readTree(before["manifest.json"])["tables"].values().map { it["name"].asText() }
        val changed = setOf("memberships", "users", "audit_log", "files", "invoices")
        for (name in tables - changed) assertThat(rows(again, name)).withFailMessage { "$name differs after the round trip" }.isEqualTo(moved(name))
        // Deleting the account retired its invoice links, so the invoice has a new one.
        assertThat(without(rows(again, "invoices"), "public_token")).isEqualTo(without(moved("invoices"), "public_token"))
        val oldLink = rows(before, "invoices").single()["public_token"].asText()
        val newLink = rows(again, "invoices").single()["public_token"].asText()
        assertThat(newLink).isNotEqualTo(oldLink)
        assertThat(client().get("/api/v1/public/invoices/$oldLink").status).isEqualTo(404)
        client().get("/api/v1/public/invoices/$newLink").expect(200)
        assertThat(without(rows(again, "memberships").filter { it["email"].asText() != importer.email }, "user_id", "status"))
            .isEqualTo(without(moved("memberships"), "user_id", "status"))
        assertThat(rows(again, "users").map { it["email"].asText() }).containsExactly(importer.email)
        // Files get storage keys from this instance.
        assertThat(without(rows(again, "files"), "storage_key")).isEqualTo(without(moved("files"), "storage_key"))
        val audit = rows(again, "audit_log")
        assertThat(without(audit.filter { it["action"].asText() !in setOf("accounts.imported", "account_exports.insert", "account_exports.update") }, "actor_user_id"))
            .isEqualTo(without(moved("audit_log").filter { it["action"].asText() !in setOf("account_exports.insert", "account_exports.update") }, "actor_user_id"))

        // The same export can't be imported twice.
        importer.request(HttpMethod.POST, "/api/v1/accounts/import", first, headers = mapOf("Content-Type" to "application/zip")).expectError(409, "account_exists")
    }

    @Test
    fun `damaged or foreign files are refused before anything is written`() {
        val s = seed()
        val zip = export(s.admin)
        val c = signup(accountName = "Elsewhere")
        fun send(bytes: ByteArray) = c.request(HttpMethod.POST, "/api/v1/accounts/import", bytes, headers = mapOf("Content-Type" to "application/zip"))
        send("not a zip".toByteArray()).expectError(400, "not_an_export")
        // One changed byte in a data file.
        val parts = unzip(zip).toMutableMap()
        parts["data/time_entries.json"] = parts["data/time_entries.json"]!!.toString(Charsets.UTF_8).replace("Map legend", "Map legenD").toByteArray()
        send(rezip(parts)).expectError(400, "export_damaged")
        send(rezip(mapOf("hello.txt" to "hi".toByteArray()))).expectError(400, "not_an_export")
        val newer = unzip(zip).toMutableMap()
        newer["manifest.json"] = mapper.writeValueAsBytes((mapper.readTree(newer["manifest.json"]) as tools.jackson.databind.node.ObjectNode).put("version", 99))
        send(rezip(newer)).expectError(400, "export_too_new")
    }

    @Test
    fun `a lapsed account is read-only but can still export its data (AT-6_3)`() {
        val s = seed()
        tx.system { dsl.update(ACCOUNTS).set(ACCOUNTS.STATUS, "lapsed").set(ACCOUNTS.LAPSED_AT, Instant.now()).where(ACCOUNTS.ID.eq(s.admin.accountId)).execute() }
        s.admin.post("/api/v1/clients", mapOf("name" to "New client", "currency" to "EUR")).expectError(402, "account_read_only")
        assertThat(unzip(export(s.admin)).keys).contains("data/time_entries.json")
    }

    /** Changes a data file inside an export and fixes its checksum, as an attacker would. */
    private fun tamper(zip: ByteArray, table: String, change: (tools.jackson.databind.node.ArrayNode) -> Unit): ByteArray {
        val parts = unzip(zip).toMutableMap()
        val rows = mapper.readTree(parts["data/$table.json"]) as tools.jackson.databind.node.ArrayNode
        change(rows)
        val bytes = mapper.writeValueAsBytes(rows)
        parts["data/$table.json"] = bytes
        val manifest = mapper.readTree(parts["manifest.json"]) as tools.jackson.databind.node.ObjectNode
        manifest["tables"].forEach { if (it["name"].asText() == table) (it as tools.jackson.databind.node.ObjectNode).put("sha256", sha256(bytes)) }
        parts["manifest.json"] = mapper.writeValueAsBytes(manifest)
        return rezip(parts)
    }

    @Test
    fun `a crafted export can't write into another account or point at its data`() {
        val victim = signup(accountName = "Victim Ltd")
        val victimProject = createProject(victim).id()
        val s = seed()
        val zip = export(s.admin)
        val accountId = s.admin.accountId!!
        // Free the account id, as if importing elsewhere.
        tx.system { deletions.purge(accountId) }
        val attacker = signup(accountName = "Attacker")
        fun send(bytes: ByteArray) = attacker.request(HttpMethod.POST, "/api/v1/accounts/import", bytes, headers = mapOf("Content-Type" to "application/zip"))

        // A client row that names the victim's account.
        send(tamper(zip, "clients") { rows -> (rows[0] as tools.jackson.databind.node.ObjectNode).put("account_id", victim.accountId.toString()) })
            .expectError(400, "export_damaged")
        // A time entry of the imported account on the victim's project.
        send(tamper(zip, "time_entries") { rows -> (rows[0] as tools.jackson.databind.node.ObjectNode).put("project_id", victimProject.toString()) })
            .expectError(400, "export_damaged")
        assertThat(victim.get("/api/v1/clients")["data"]).hasSize(1)

        // A storage key pointing elsewhere is replaced, never used.
        val imported = send(tamper(zip, "files") { rows -> (rows[0] as tools.jackson.databind.node.ObjectNode).put("storage_key", "exports/${victim.accountId}/x.zip") }).expect(201)
        assertThat(imported["files"].asInt()).isEqualTo(1)
        val importedId = UUID.fromString(imported["account_id"].asText())
        val key = tx.system { dsl.select(com.honestrobin.time.db.Tables.FILES.STORAGE_KEY).from(com.honestrobin.time.db.Tables.FILES).where(com.honestrobin.time.db.Tables.FILES.ACCOUNT_ID.eq(importedId)).fetchOne()!!.value1() }
        assertThat(key).startsWith("$importedId/")
    }

    private fun rezip(parts: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }
}
