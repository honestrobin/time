// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers

import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.support.HarvestFixture
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockHarvest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Spec §6.6 and AT-2.4: Harvest's CSV exports, read with a preview and a column mapping. */
class CsvImportTest : IntegrationTest() {

    private fun upload(admin: TestClient, name: String, csv: String) =
        admin.upload("/api/v1/imports/csv", "file", name, "text/csv", csv.toByteArray()).expect(201)

    private fun quote(v: Any?) = (v?.toString() ?: "").let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"" + it.replace("\"", "\"\"") + "\"" else it }

    /** The fixture as Harvest's detailed time report export. */
    private fun timeReport(f: HarvestFixture): String {
        val header = listOf("Date", "Client", "Project", "Project Code", "Task", "Notes", "Hours", "Hours Rounded", "Billable?", "Invoiced?", "Approved?",
            "First Name", "Last Name", "Roles", "Employee?", "Billable Rate", "Billable Amount", "Cost Rate", "Cost Amount", "Currency", "External Reference URL")
        val lines = f.timeEntries.map { e ->
            val project = f.projectOf((e["project"] as Map<*, *>)["id"] as Long)
            val client = project["client"] as Map<*, *>
            val task = f.tasks.first { it["id"] == (e["task"] as Map<*, *>)["id"] }
            val user = f.users.first { it["id"] == (e["user"] as Map<*, *>)["id"] }
            val hours = e["hours"] as BigDecimal
            val rate = e["billable_rate"] as BigDecimal?
            listOf(
                e["spent_date"], client["name"], project["name"], project["code"], task["name"], e["notes"], hours, hours,
                if (e["billable"] == true) "Yes" else "No", if (e["is_billed"] == true) "Yes" else "No", if (e["approval_status"] == "approved") "Yes" else "No",
                user["first_name"], user["last_name"], "", "Yes", rate ?: "", rate?.let { (it * hours).setScale(2, java.math.RoundingMode.HALF_UP) } ?: "",
                user["cost_rate"] ?: "", "", client["currency"], (e["external_reference"] as Map<*, *>?)?.get("permalink") ?: "",
            ).joinToString(",") { quote(it) }
        }
        return (listOf(header.joinToString(",")) + lines).joinToString("\n")
    }

    /** Time and billable amounts per client and project, as the reports would add them up. */
    private fun totals(account: UUID): Map<String, Pair<Long, Long>> = tx.system {
        dsl.select(CLIENTS.NAME, PROJECTS.NAME, DSL.sum(TIME_ENTRIES.DURATION_SECONDS), DSL.sum(TIME_ENTRIES.DURATION_SECONDS.mul(TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT).div(3600)))
            .from(TIME_ENTRIES).join(PROJECTS).on(PROJECTS.ID.eq(TIME_ENTRIES.PROJECT_ID)).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .where(TIME_ENTRIES.ACCOUNT_ID.eq(account))
            .groupBy(CLIENTS.NAME, PROJECTS.NAME)
            .fetch().associate { "${it.value1()} / ${it.value2()}" to (it.value3().toLong() to it.value4().toLong()) }
    }

    private fun entries(account: UUID) = tx.system { dsl.fetchCount(TIME_ENTRIES, TIME_ENTRIES.ACCOUNT_ID.eq(account)) }

    @Test
    fun `the CSV export gives the same totals as the API import (AT-2_4), and imports again without duplicates`() {
        val f = HarvestFixture.agency(seed = 41, people = 3, clients = 2, projectsPerClient = 2, weeks = 6)
        MockHarvest.reset(f)
        val viaApi = signup(currency = "USD")
        viaApi.post("/api/v1/imports/harvest", mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID)).expect(201)

        val viaCsv = signup(currency = "USD")
        val csv = timeReport(f)
        val preview = upload(viaCsv, "harvest_time_report.csv", csv)
        assertThat(preview["kind"].asText()).isEqualTo("TIME")
        assertThat(preview["problem_count"].asInt()).isZero()
        assertThat(preview["valid"].asInt()).isEqualTo(f.timeEntries.size)
        assertThat(preview["creates"]["people"].size()).isEqualTo(f.users.size)
        assertThat(preview["creates"]["projects"].size()).isEqualTo(f.timeEntries.map { (it["project"] as Map<*, *>)["id"] }.distinct().size)
        val result = viaCsv.post("/api/v1/imports/${preview["job_id"].asText()}/csv/commit", emptyMap<String, Any>()).expect(200)
        assertThat(result["status"].asText()).isEqualTo("completed")
        assertThat(result["stats"]["entries_created"].asInt()).isEqualTo(f.timeEntries.size)

        assertThat(entries(viaCsv.accountId!!)).isEqualTo(entries(viaApi.accountId!!))
        assertThat(totals(viaCsv.accountId!!)).isEqualTo(totals(viaApi.accountId!!))

        // The same file again changes nothing.
        val again = upload(viaCsv, "harvest_time_report.csv", csv)
        val second = viaCsv.post("/api/v1/imports/${again["job_id"].asText()}/csv/commit", emptyMap<String, Any>()).expect(200)
        assertThat(second["stats"]["entries_created"].asInt()).isZero()
        assertThat(entries(viaCsv.accountId!!)).isEqualTo(f.timeEntries.size)
    }

    @Test
    fun `other headers are mapped by hand, and day-first dates and decimal commas are read`() {
        val admin = signup(currency = "EUR")
        val csv = """
            Datum;Kunde;Projekt;Tätigkeit;Stunden;Mitarbeiter;Notiz;Satz
            02.03.2026;Müller GmbH;Website;Design;1,5;Anna Schmidt;Startseite;95,00
            02.03.2026;Müller GmbH;Website;Design;1,5;Anna Schmidt;Startseite;95,00
            13.03.2026;Müller GmbH;Website;Meetings;0,75;Jonas Weber;;
            31.02.2026;Müller GmbH;Website;Design;1;Anna Schmidt;Kaputt;95
        """.trimIndent()
        val first = upload(admin, "zeiten.csv", csv)
        val id = first["job_id"].asText()
        // Nothing is recognised: the preview asks for the columns.
        assertThat(first["problems"].map { it["message"].asText() }).anyMatch { it.contains("Choose the column") }

        val mapping = mapOf("kind" to "TIME", "mapping" to mapOf(
            "date" to "Datum", "client" to "Kunde", "project" to "Projekt", "task" to "Tätigkeit", "hours" to "Stunden", "person" to "Mitarbeiter",
            "notes" to "Notiz", "billable_rate" to "Satz",
        ), "people" to mapOf("Anna Schmidt" to "anna@mueller.example"))
        val preview = admin.post("/api/v1/imports/$id/csv/preview", mapping).expect(200)
        assertThat(preview["date_order"].asText()).isEqualTo("DMY")
        assertThat(preview["valid"].asInt()).isEqualTo(3)
        assertThat(preview["problems"].map { it["row"].asInt() to it["message"].asText() }).containsExactly(5 to "\"31.02.2026\" isn't a date")
        assertThat(preview["total_hours"].decimalValue()).isEqualByComparingTo("3.75")
        assertThat(preview["creates"]["people"].map { it["name"].asText() to it["email"].asText(null) })
            .containsExactlyInAnyOrder("Anna Schmidt" to "anna@mueller.example", "Jonas Weber" to null)

        val result = admin.post("/api/v1/imports/$id/csv/commit", emptyMap<String, Any>()).expect(200)
        assertThat(result["stats"]["entries_created"].asInt()).isEqualTo(3) // the two identical rows are both kept
        assertThat(result["skipped"].asInt()).isEqualTo(1)
        tx.system {
            val emails = dsl.select(MEMBERSHIPS.NAME, MEMBERSHIPS.EMAIL, MEMBERSHIPS.STATUS).from(MEMBERSHIPS).where(MEMBERSHIPS.ACCOUNT_ID.eq(admin.accountId)).and(MEMBERSHIPS.STATUS.eq("pending_invite"))
                .fetch().associate { it.value1() to it.value2() }
            assertThat(emails).containsEntry("Anna Schmidt", "anna@mueller.example").containsEntry("Jonas Weber", "jonas.weber@import.invalid")
            val billed = dsl.select(TIME_ENTRIES.DURATION_SECONDS, TIME_ENTRIES.BILLABLE, TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT).from(TIME_ENTRIES)
                .where(TIME_ENTRIES.ACCOUNT_ID.eq(admin.accountId)).and(TIME_ENTRIES.NOTES.eq("Startseite")).fetch()
            assertThat(billed.map { Triple(it.value1(), it.value2(), it.value3()) }).containsOnly(Triple(5400, true, 9500L))
        }
        // The file can't be imported twice from the same upload.
        admin.post("/api/v1/imports/$id/csv/commit", emptyMap<String, Any>()).expectError(409, "not_in_preview")
        assertThat(admin.get("/api/v1/imports/$id/issues").expect(200).body.map { it["external_id"].asText() }).containsExactly("5")
    }

    @Test
    fun `ambiguous dates wait for the person to say day-first or month-first`() {
        val admin = signup()
        val csv = "Date,Client,Project,Task,Hours,First Name,Last Name\n01/02/2026,Acme,Site,Build,2,Ada,Lovelace\n03/04/2026,Acme,Site,Build,1,Ada,Lovelace\n"
        val p = upload(admin, "time.csv", csv)
        assertThat(p["date_order_needed"].asBoolean()).isTrue()
        val id = p["job_id"].asText()
        admin.post("/api/v1/imports/$id/csv/commit", emptyMap<String, Any>()).expectError(400, "date_order_needed")
        admin.post("/api/v1/imports/$id/csv/commit", mapOf("date_order" to "MDY")).expect(200)
        tx.system {
            assertThat(dsl.select(TIME_ENTRIES.SPENT_DATE).from(TIME_ENTRIES).where(TIME_ENTRIES.ACCOUNT_ID.eq(admin.accountId)).fetch(TIME_ENTRIES.SPENT_DATE).map { it.toString() })
                .containsExactlyInAnyOrder("2026-01-02", "2026-03-04")
        }
    }

    @Test
    fun `clients, contacts and projects exports`() {
        val admin = signup(currency = "GBP")
        val clients = upload(admin, "clients.csv", "Client Name,Client Address,Client Currency\nFjord & Pine,\"1 Harbour Road\nBergen\",NOK\nTui Studio,,\n")
        assertThat(clients["kind"].asText()).isEqualTo("CLIENTS")
        admin.post("/api/v1/imports/${clients["job_id"].asText()}/csv/commit", emptyMap<String, Any>()).expect(200)
        val contacts = upload(admin, "contacts.csv", "Client,Title,First Name,Last Name,Email,Office Phone\nFjord & Pine,Finance,Ingrid,Berg,ingrid@fjord.example,+47 555 0100\nNew Client Ltd,,Sam,Lee,,\n")
        assertThat(contacts["kind"].asText()).isEqualTo("CONTACTS")
        assertThat(contacts["creates"]["clients"].map { it.asText() }).containsExactly("New Client Ltd")
        admin.post("/api/v1/imports/${contacts["job_id"].asText()}/csv/commit", emptyMap<String, Any>()).expect(200)
        val projects = upload(admin, "projects.csv", "Client,Project,Project Code,Billable?,Project Notes\nFjord & Pine,Brand refresh,FP-1,Yes,Logo and type\nTui Studio,Internal,,No,\n")
        assertThat(projects["kind"].asText()).isEqualTo("PROJECTS")
        admin.post("/api/v1/imports/${projects["job_id"].asText()}/csv/commit", emptyMap<String, Any>()).expect(200)
        tx.system {
            val c = dsl.selectFrom(CLIENTS).where(CLIENTS.ACCOUNT_ID.eq(admin.accountId)).fetch().associateBy { it.name }
            assertThat(c.keys).containsExactlyInAnyOrder("Fjord & Pine", "Tui Studio", "New Client Ltd")
            assertThat(c.getValue("Fjord & Pine").currency).isEqualTo("NOK")
            assertThat(c.getValue("Fjord & Pine").addressLine1).isEqualTo("1 Harbour Road")
            assertThat(c.getValue("Tui Studio").currency).isEqualTo("GBP")
            assertThat(dsl.fetchExists(CLIENT_CONTACTS, CLIENT_CONTACTS.EMAIL.eq("ingrid@fjord.example").and(CLIENT_CONTACTS.NAME.eq("Ingrid Berg")))).isTrue()
            val p = dsl.selectFrom(PROJECTS).where(PROJECTS.ACCOUNT_ID.eq(admin.accountId)).fetch().associateBy { it.name }
            assertThat(p.getValue("Brand refresh").code).isEqualTo("FP-1")
            assertThat(p.getValue("Internal").isBillable).isFalse()
        }
    }
}
