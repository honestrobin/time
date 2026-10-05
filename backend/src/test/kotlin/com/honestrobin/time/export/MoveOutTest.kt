// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.API_TOKENS
import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.edition.Edition
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Condition
import org.jooq.Table
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * "You can always leave" in the Robin's Code: one Move out button gets everything ready for
 * leaving, on every plan and in every state of an account, and changes nothing in it.
 */
class MoveOutTest : IntegrationTest() {
    @Autowired lateinit var props: HonestRobinProperties

    private class Seeded(val admin: TestClient, val invoiceNumber: String)

    /** An account with a client, a project, some time and an issued invoice. */
    private fun seed(accountName: String): Seeded {
        val admin = signup(accountName = accountName, email = uniqueEmail("owner"))
        membershipId(admin)
        val task = createTask(admin, "Design")
        val client = createClient(admin, "Kiwi Outdoor Ltd", "NZD")
        val project = createProject(admin, client, listOf(task), extra = mapOf("bill_by" to "project", "hourly_rate" to 12_000)).id()
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 5400, "notes" to "Trail map")).expect(201)
        val invoice = admin.post("/api/v1/invoices", mapOf("client_id" to client, "from_time" to mapOf("from" to "2026-09-01", "to" to "2026-09-30"))).expect(201).id()
        val number = admin.post("/api/v1/invoices/$invoice/mark_sent").expect(200)["number"].asText()
        return Seeded(admin, number)
    }

    /** Waits for the background job to finish the export, and returns the zip's files by name. */
    private fun download(admin: TestClient, exportId: String): Map<String, ByteArray> {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            val e = admin.get("/api/v1/exports").expect(200).body.first { it["id"].asText() == exportId }
            if (e["status"].asText() == "ready") break
            assertThat(e["status"].asText()).withFailMessage { "Export failed: $e" }.isIn("queued", "running")
            check(System.currentTimeMillis() < deadline) { "Export did not finish: $e" }
            Thread.sleep(100)
        }
        val zip = admin.get("/api/v1/exports/$exportId/download").expect(200).bytes
        return ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry }.associate { it.name to z.readAllBytes() } }
    }

    /**
     * Every row the account has, in every table that holds its data or its connections. The
     * export's own record (account_exports) and the audit log are left out: making an export
     * writes those, and the test looks at the audit log separately.
     */
    private fun snapshot(accountId: UUID): Map<String, List<List<String?>>> = tx.system {
        fun byAccount(t: Table<*>): Condition = t.field("account_id", UUID::class.java)!!.eq(accountId)
        val tables = ExportFormat.TABLES.filter { it.table != AUDIT_LOG }.map { it.table to it.rows(accountId) } +
            listOf(INTEGRATIONS, API_TOKENS, IMPORT_JOBS, SUBSCRIPTIONS).map { it to byAccount(it) }
        tables.associate { (t, rows) ->
            t.name to dsl.select().from(t).where(rows).orderBy(t.primaryKey!!.fields).fetch().map { r -> r.intoList().map(::text) }
        }
    }

    private fun text(v: Any?): String? = when (v) {
        null -> null
        is ByteArray -> HexFormat.of().formatHex(v)
        is Array<*> -> v.contentDeepToString()
        else -> v.toString()
    }

    private fun setStatus(accountId: UUID, status: String) = tx.system {
        dsl.update(ACCOUNTS).set(ACCOUNTS.STATUS, status).set(ACCOUNTS.LAPSED_AT, Instant.now()).where(ACCOUNTS.ID.eq(accountId)).execute()
    }

    /** A Honest Robin Cloud subscription as Paddle's webhooks leave it. */
    private fun subscribe(accountId: UUID, status: String, seats: Int) = tx.system {
        dsl.insertInto(SUBSCRIPTIONS).set(SUBSCRIPTIONS.ACCOUNT_ID, accountId).set(SUBSCRIPTIONS.EXTERNAL_ID, "sub_moveout_${UUID.randomUUID()}")
            .set(SUBSCRIPTIONS.EXTERNAL_PRICE_ID, "pri_moveout").set(SUBSCRIPTIONS.BILLING_INTERVAL, "month").set(SUBSCRIPTIONS.STATUS, status)
            .set(SUBSCRIPTIONS.SEATS, seats).set(SUBSCRIPTIONS.CURRENCY, "EUR").set(SUBSCRIPTIONS.LOCKED_UNIT_PRICE_MINOR, 900L)
            .apply { if (status == "canceled") set(SUBSCRIPTIONS.CANCELED_AT, Instant.now()) }
            .execute()
    }

    private fun auditIds(accountId: UUID) = tx.system { dsl.select(AUDIT_LOG.ID).from(AUDIT_LOG).where(AUDIT_LOG.ACCOUNT_ID.eq(accountId)).fetch(AUDIT_LOG.ID).toSet() }

    @Test
    fun `move out works in every state of an account and changes nothing`() {
        val cloud = props.edition == Edition.CLOUD
        // Cancelled is a state of Honest Robin Cloud: a Team subscription that ended.
        val states = listOfNotNull("free", "team", "lapsed", "waiting to be deleted", if (cloud) "cancelled" else null)
        for (state in states) {
            val s = seed("Kowhai Studio")
            val accountId = s.admin.accountId!!
            try {
                when (state) {
                    "team" -> {
                        invite(s.admin)
                        if (cloud) subscribe(accountId, "active", seats = 2)
                    }
                    "lapsed" -> setStatus(accountId, "lapsed")
                    "waiting to be deleted" -> s.admin.post("/api/v1/account/deletion", mapOf("confirm_name" to "Kowhai Studio")).expect(204)
                    "cancelled" -> {
                        invite(s.admin)
                        subscribe(accountId, "canceled", seats = 2)
                        // More people than the free plan, so the account turned read-only.
                        setStatus(accountId, "lapsed")
                    }
                }
                val status = s.admin.get("/api/v1/account").expect(200)["status"].asText()
                val before = snapshot(accountId)
                val audit = auditIds(accountId)

                // One step, no questions.
                val moved = s.admin.post("/api/v1/account/move_out").expect(202)
                assertThat(moved["export"]["status"].asText()).describedAs(state).isIn("queued", "running", "ready")
                assertThat(moved["connections"].values().map { it["kind"].asText() }).describedAs(state).contains("invoice_links")
                assertThat(s.admin.get("/api/v1/account/connections").expect(200).body).describedAs(state).isEqualTo(moved["connections"])

                // Everything, in one download, with where to go next and what's still connected.
                val zip = download(s.admin, moved["export"]["id"].asText())
                assertThat(zip.keys).describedAs(state).contains("manifest.json", "data/time_entries.json", "csv/time-entries.csv", "invoices/invoice-${s.invoiceNumber}.pdf")
                assertThat(zip["invoices/invoice-${s.invoiceNumber}.pdf"]!!.copyOf(4).toString(Charsets.US_ASCII)).isEqualTo("%PDF")
                val readme = zip["README.txt"]!!.toString(Charsets.UTF_8)
                assertThat(readme).describedAs(state)
                    .contains("WHERE YOU CAN GO NEXT", LeavingGuide.SELF_HOSTING_GUIDE, "Import an export", "csv/")
                    .contains("STILL CONNECTED", "Invoice links: your clients can open 1 invoice from the links they were sent.")
                    .contains("CANCELLING AND DELETING", "Making this export changed nothing in the account")

                // Nothing changed: not the state, not the data, not a connection. The only new
                // audit entries are the export's own record.
                assertThat(s.admin.get("/api/v1/account").expect(200)["status"].asText()).describedAs(state).isEqualTo(status)
                assertThat(snapshot(accountId)).describedAs(state).isEqualTo(before)
                val added = tx.system {
                    dsl.select(AUDIT_LOG.ENTITY_TYPE).from(AUDIT_LOG).where(AUDIT_LOG.ACCOUNT_ID.eq(accountId)).and(AUDIT_LOG.ID.notIn(audit)).fetch(AUDIT_LOG.ENTITY_TYPE)
                }
                assertThat(added).describedAs(state).isNotEmpty().allMatch { it == "account_exports" }
            } finally {
                // An account left waiting would be deleted by the next test that moves the clock past the grace period.
                if (state == "waiting to be deleted") s.admin.delete("/api/v1/account/deletion")
                tx.system { dsl.deleteFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(accountId)).execute() }
            }
        }
    }

    @Test
    fun `move out lists everything still connected to the account, and where to end each`() {
        val s = seed("Totara Works")
        val admin = s.admin
        val accountId = admin.accountId!!
        val member = invite(admin)
        try {
            // Tokens and a signed-in device. An expired token connects nothing.
            admin.post("/api/v1/me/api_tokens", mapOf("name" to "Reports script", "scopes" to listOf("read"))).expect(201)
            admin.post("/api/v1/me/api_tokens", mapOf("name" to "Old script", "expires_at" to Instant.now().minus(Duration.ofDays(1)).toString())).expect(201)
            member.post("/api/v1/me/api_tokens", mapOf("name" to "Mo's timer", "scopes" to listOf("read", "write"))).expect(201)
            val device = client().apply { sendCsrf = false }
            val start = device.post("/api/v1/auth/device", mapOf("client_name" to "Browser extension (Firefox)")).expect(200)
            admin.post("/api/v1/device_authorizations/${start["user_code"].asText()}/approve").expect(204)
            device.post("/api/v1/auth/device/token", mapOf("device_code" to start["device_code"].asText())).expect(200)
            admin.patch("/api/v1/invoice_settings", mapOf("reminders_enabled" to true)).expect(200)
            // Outside services, as connecting them leaves them (each module's own tests connect them for real).
            tx.system {
                for ((kind, mode, name) in listOf(
                    Triple("stripe", "connect", "Totara Works Ltd"),
                    Triple("qbo", "connect", "Totara Books"),
                    Triple("xero", "connect", "Totara Ledger"),
                    Triple("storecove", "api_key", "Storecove"),
                )) {
                    dsl.insertInto(INTEGRATIONS).set(INTEGRATIONS.ACCOUNT_ID, accountId).set(INTEGRATIONS.KIND, kind).set(INTEGRATIONS.MODE, mode)
                        .set(INTEGRATIONS.STATUS, "connected").set(INTEGRATIONS.EXTERNAL_ACCOUNT_ID, "moveout_${UUID.randomUUID()}").set(INTEGRATIONS.DISPLAY_NAME, name)
                        .execute()
                }
            }
            // A Harvest sync that runs for three more days, and an import that stopped and keeps its token.
            tx.system {
                dsl.insertInto(IMPORT_JOBS).set(IMPORT_JOBS.ACCOUNT_ID, accountId).set(IMPORT_JOBS.MODE, "api").set(IMPORT_JOBS.STATUS, "syncing")
                    .set(IMPORT_JOBS.EXTERNAL_ACCOUNT_ID, "1234567").set(IMPORT_JOBS.TOKEN_ENCRYPTED, "not-a-real-token")
                    .set(IMPORT_JOBS.SYNC_UNTIL, Instant.now().plus(Duration.ofDays(3))).execute()
            }
            tx.system {
                dsl.insertInto(IMPORT_JOBS).set(IMPORT_JOBS.ACCOUNT_ID, accountId).set(IMPORT_JOBS.MODE, "api").set(IMPORT_JOBS.STATUS, "failed")
                    .set(IMPORT_JOBS.EXTERNAL_ACCOUNT_ID, "7654321").set(IMPORT_JOBS.TOKEN_ENCRYPTED, "not-a-real-token").execute()
            }

            // Only admins see it, as only they can export.
            member.get("/api/v1/account/connections").expectError(403, "forbidden")
            member.post("/api/v1/account/move_out").expectError(403, "forbidden")

            val list = admin.get("/api/v1/account/connections").expect(200).body.values().toList()
            assertThat(list.map { it["kind"].asText() }).containsExactlyInAnyOrder(
                "api_token", "api_token", "device", "stripe", "qbo", "xero", "storecove", "harvest_sync", "harvest_import", "invoice_links", "invoice_reminders",
            )
            fun one(kind: String, name: String? = null): JsonNode = list.single { it["kind"].asText() == kind && (name == null || it["name"].asText() == name) }
            fun JsonNode.text(field: String): String? = get(field)?.takeIf { !it.isNull }?.asText()
            assertThat(list.map { it.text("name") }).doesNotContain("Old script")
            assertThat(one("api_token", "Reports script").text("person")).isEqualTo("Ada Admin")
            assertThat(one("api_token", "Reports script").text("membership_id")).isEqualTo(admin.membershipId.toString())
            assertThat(one("api_token", "Mo's timer").text("person")).isEqualTo("Mo Member")
            assertThat(one("device").text("name")).isEqualTo("Browser extension (Firefox)")
            assertThat(one("device").text("ends_at")).isNotNull()
            assertThat(one("stripe").text("name")).isEqualTo("Totara Works Ltd")
            assertThat(one("storecove").text("mode")).isEqualTo("api_key")
            assertThat(one("harvest_sync").text("name")).isEqualTo("1234567")
            assertThat(one("harvest_sync").text("ends_at")).isNotNull()
            assertThat(one("invoice_links")["count"].asInt()).isEqualTo(1)
            // Where each one is ended in Time; invoice links end only with the account.
            assertThat(list.associate { "${it["kind"].asText()}:${it.text("name")}" to it.text("end_in") }).containsAllEntriesOf(
                mapOf(
                    "api_token:Reports script" to "/settings/profile", "device:Browser extension (Firefox)" to "/settings/profile",
                    "stripe:Totara Works Ltd" to "/settings/payments", "qbo:Totara Books" to "/settings/accounting", "xero:Totara Ledger" to "/settings/accounting",
                    "storecove:Storecove" to "/settings/invoices", "harvest_sync:1234567" to "/settings/import", "harvest_import:7654321" to "/settings/import",
                    "invoice_links:null" to null, "invoice_reminders:null" to "/settings/invoices",
                ),
            )

            // The zip says the same, as of the moment it was made, with how to end each one.
            val moved = admin.post("/api/v1/account/move_out").expect(202)
            val readme = download(admin, moved["export"]["id"].asText())["README.txt"]!!.toString(Charsets.UTF_8)
            assertThat(readme).contains(
                "API token \"Reports script\" of Ada Admin",
                "API token \"Mo's timer\" of Mo Member",
                "Device \"Browser extension (Firefox)\", signed in as Ada Admin",
                "revokes it under Profile > Personal access tokens",
                "Stripe: your clients pay invoices online into Totara Works Ltd.",
                "Settings > Online payments > Disconnect Stripe, which ends our access at Stripe.",
                "QuickBooks Online: invoices and payments go to Totara Books.",
                "Xero: invoices and payments go to Totara Ledger.",
                "Settings > Accounting > Disconnect",
                "Peppol: e-invoices go out through Storecove, with your own Storecove API key.",
                "Then delete the API key in Storecove.",
                "Harvest sync: changes in Harvest account 1234567 come over until",
                "Harvest import: an import from Harvest account 7654321 hasn't finished",
                "delete the personal access token in Harvest",
                "Invoice links: your clients can open 1 invoice",
                "stop for good when it's deleted",
                "Invoice reminders: we email your clients about unpaid invoices",
                "Not in this list: a Team subscription to Honest Robin Cloud.",
            ).doesNotContain("Old script")
        } finally {
            tx.system {
                dsl.deleteFrom(IMPORT_JOBS).where(IMPORT_JOBS.ACCOUNT_ID.eq(accountId)).execute()
                dsl.deleteFrom(INTEGRATIONS).where(INTEGRATIONS.ACCOUNT_ID.eq(accountId)).execute()
            }
        }
    }

    @Test
    fun `move out gives every invoice as a file to open, with its e-invoice where it has one`() {
        // A German seller and a French buyer, with everything an e-invoice needs (as in EInvoiceTest).
        val admin = signup(accountName = "Fjordlicht GmbH", email = uniqueEmail("owner"))
        admin.patch(
            "/api/v1/account",
            mapOf(
                "legal_name" to "Fjordlicht GmbH", "address_line1" to "Hafenstraße 12", "postal_code" to "20457", "city" to "Hamburg",
                "country_code" to "DE", "vat_id" to "DE123456789", "iban" to "DE89370400440532013000", "bic" to "COBADEFFXXX",
                "peppol_scheme" to "9930", "peppol_id" to "DE123456789",
            ),
        ).expect(200)
        admin.patch(
            "/api/v1/invoice_settings",
            mapOf("invoice_contact_name" to "Ada Admin", "invoice_contact_email" to "billing@fjordlicht.test", "invoice_contact_phone" to "+49 40 123456"),
        ).expect(200)
        val client = admin.post(
            "/api/v1/clients",
            mapOf("name" to "Atelier Bleu SARL", "currency" to "EUR", "address_line1" to "12 rue de la Paix", "postal_code" to "75002", "city" to "Paris", "country_code" to "FR", "vat_id" to "FR40303265045", "peppol_scheme" to "9957", "peppol_id" to "FR40303265045"),
        ).expect(201).id()
        admin.post("/api/v1/clients/$client/contacts", mapOf("name" to "Claire Martin", "email" to "factures@atelierbleu.test", "is_invoice_recipient" to true)).expect(201)
        val invoice = admin.post(
            "/api/v1/invoices",
            mapOf(
                "client_id" to client, "vat_mode" to "standard", "buyer_reference" to "04011000-12345-34", "purchase_order" to "PO-2026-118",
                "lines" to listOf(mapOf("description" to "Design, September", "quantity" to 12.5, "unit_price" to 9_500, "vat_category_code" to "S", "vat_percent" to 19)),
            ),
        ).expect(201).id()
        val number = admin.post("/api/v1/invoices/$invoice/mark_sent").expect(200)["number"].asText()

        val zip = download(admin, admin.post("/api/v1/account/move_out").expect(202)["export"]["id"].asText())

        // The PDF as the client gets it: in Germany, a Factur-X hybrid with the e-invoice inside.
        val pdf = zip["invoices/invoice-$number.pdf"]!!
        val embedded = Loader.loadPDF(pdf).use { doc ->
            val names = PDDocumentNameDictionary(doc.documentCatalog).embeddedFiles as PDEmbeddedFilesNameTreeNode
            names.names?.keys.orEmpty() + names.kids.orEmpty().flatMap { it.names?.keys.orEmpty() }
        }
        assertThat(embedded).contains("factur-x.xml")
        // And the XML e-invoices, which other tools import.
        val xrechnung = zip["invoices/e-invoices/invoice-$number-xrechnung.xml"]!!.toString(Charsets.UTF_8)
        assertThat(xrechnung).contains("CrossIndustryInvoice", "04011000-12345-34")
        val peppol = zip["invoices/e-invoices/invoice-$number-peppol.xml"]!!.toString(Charsets.UTF_8)
        assertThat(peppol).contains("Invoice", "urn:fdc:peppol.eu:2017:poacc:billing:3.0")
    }
}
