// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.reports

import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import com.honestrobin.time.support.TestResponse
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipFile

/** Spec §12: time, detailed, uninvoiced, budget and expense reports, bulk edit, and exports. */
class ReportsTest : IntegrationTest() {

    private class Fixture(
        val admin: TestClient,
        val member: TestClient,
        val design: UUID,
        val dev: UUID,
        val eurClient: UUID,
        val usdClient: UUID,
        val projectA: UUID,
        val projectB: UUID,
        val adminDesign: UUID,
        val adminDev: UUID,
        val memberDev: UUID,
    )

    private val september = mapOf("from" to "2026-09-01", "to" to "2026-09-30")

    /**
     * Two clients in two currencies, 15-minute rounding (up):
     * - Ada, Fjord & Pine / Design, 1h10m billable (rounds to 1h15m, €125.00)
     * - Ada, Fjord & Pine / Development, 30m non-billable
     * - Mo, Maple Labs / Development, 2h billable ($160.00), cost rate 40.00/h (€80.00)
     */
    private fun fixture(): Fixture {
        val admin = signup()
        membershipId(admin)
        admin.patch("/api/v1/account", mapOf("time_rounding_minutes" to 15)).expect(200)
        val member = invite(admin, extra = mapOf("cost_rate" to 4_000))
        val design = createTask(admin, "Design")
        val dev = createTask(admin, "Development")
        val eurClient = createClient(admin, "Fjord & Pine", "EUR")
        val usdClient = createClient(admin, "Maple Labs", "USD")
        val billByProject = { rate: Long -> mapOf("bill_by" to "project", "hourly_rate" to rate) }
        val projectA = createProject(admin, eurClient, listOf(design, dev), listOf(member), billByProject(10_000)).id()
        val projectB = createProject(admin, usdClient, listOf(dev), listOf(member), billByProject(8_000)).id()
        fun track(c: TestClient, project: UUID, task: UUID, date: String, seconds: Int, extra: Map<String, Any?> = emptyMap()) =
            c.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to date, "duration_seconds" to seconds) + extra).expect(201).id()
        val adminDesign = track(admin, projectA, design, "2026-09-14", 4200, mapOf("notes" to "=1+2, logo"))
        val adminDev = track(admin, projectA, dev, "2026-09-15", 1800, mapOf("billable" to false))
        val memberDev = track(member, projectB, dev, "2026-09-15", 7200)
        return Fixture(admin, member, design, dev, eurClient, usdClient, projectA, projectB, adminDesign, adminDev, memberDev)
    }

    private fun JsonNode.row(name: String): JsonNode = this["rows"].first { it["name"].asText() == name }

    private fun TestResponse.row(name: String): JsonNode = body.row(name)

    private fun JsonNode.amount(currency: String): Long = this["amounts"].first { it["currency"].asText() == currency }["billable_amount"].asLong()

    private fun TestResponse.amount(currency: String): Long = body.amount(currency)

    @Test
    fun `the time report groups by client, project, task and person with rounded hours and amounts per currency`() {
        val f = fixture()
        val byClient = f.admin.get("/api/v1/reports/time", september + ("group_by" to "client")).expect(200)
        assertThat(byClient["rows"].map { it["name"].asText() }).containsExactly("Fjord & Pine", "Maple Labs")
        assertThat(byClient.row("Fjord & Pine")["seconds"].asLong()).isEqualTo(4500 + 1800L)
        assertThat(byClient.row("Fjord & Pine")["billable_seconds"].asLong()).isEqualTo(4500L)
        assertThat(byClient.row("Fjord & Pine").amount("EUR")).isEqualTo(12_500)
        assertThat(byClient.row("Maple Labs").amount("USD")).isEqualTo(16_000)
        assertThat(byClient.row("Maple Labs")["cost_amount"].asLong()).isEqualTo(8_000)
        assertThat(byClient["seconds"].asLong()).isEqualTo(13_500)
        assertThat(byClient["billable_seconds"].asLong()).isEqualTo(11_700)
        assertThat(byClient["entry_count"].asInt()).isEqualTo(3)
        assertThat(byClient["amounts"].map { it["currency"].asText() }).containsExactly("EUR", "USD")
        assertThat(byClient.amount("EUR")).isEqualTo(12_500)
        assertThat(byClient["cost_currency"].asText()).isEqualTo("EUR")
        assertThat(byClient["rounding_minutes"].asInt()).isEqualTo(15)

        val byProject = f.admin.get("/api/v1/reports/time", september + ("group_by" to "project")).expect(200)
        assertThat(byProject["rows"].map { it["client_name"].asText() }).containsExactlyInAnyOrder("Fjord & Pine", "Maple Labs")

        // Development spans both currencies; its non-billable euro work adds no amount.
        val byTask = f.admin.get("/api/v1/reports/time", september + ("group_by" to "task")).expect(200)
        assertThat(byTask.row("Development")["seconds"].asLong()).isEqualTo(9000L)
        assertThat(byTask.row("Development")["amounts"].map { it["currency"].asText() }).containsExactly("USD")

        val byPerson = f.admin.get("/api/v1/reports/time", september + ("group_by" to "person")).expect(200)
        assertThat(byPerson["rows"].map { it["name"].asText() to it["seconds"].asLong() }).containsExactly("Ada Admin" to 6300L, "Mo Member" to 7200L)

        // Filters.
        fun total(params: Map<String, Any?>) = f.admin.get("/api/v1/reports/time", september + params).expect(200)["seconds"].asLong()
        assertThat(total(mapOf("billable" to false))).isEqualTo(1800)
        assertThat(total(mapOf("membership_id" to f.member.membershipId))).isEqualTo(7200)
        assertThat(total(mapOf("client_id" to f.eurClient))).isEqualTo(6300)
        assertThat(total(mapOf("project_id" to "${f.projectA},${f.projectB}"))).isEqualTo(13_500)
        assertThat(total(mapOf("task_id" to f.design))).isEqualTo(4500)
        assertThat(total(mapOf("approval_state" to "draft"))).isEqualTo(13_500)
        assertThat(total(mapOf("invoiced" to true))).isEqualTo(0)
        val team = f.admin.post("/api/v1/teams", mapOf("name" to "Developers", "membership_ids" to listOf(f.member.membershipId))).expect(201).id()
        assertThat(total(mapOf("team_id" to team))).isEqualTo(7200)
        assertThat(total(mapOf("from" to "2026-09-15", "to" to "2026-09-15"))).isEqualTo(9000)

        f.admin.get("/api/v1/reports/time", mapOf("group_by" to "colour")).expectError(422, "validation_failed")
        f.admin.get("/api/v1/reports/time", mapOf("from" to "2026-09-30", "to" to "2026-09-01")).expectError(422, "validation_failed")
    }

    @Test
    fun `a running timer counts up to now, rounded like everything else`() {
        val f = fixture()
        val today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Zagreb")))
        val timer = f.admin.post("/api/v1/time_entries", mapOf("project_id" to f.projectA, "task_id" to f.design, "spent_date" to today.toString())).expect(201)
        assertThat(timer["is_running"].asBoolean()).isTrue()
        clock.advance(Duration.ofMinutes(20))
        val report = f.admin.get("/api/v1/reports/time", mapOf("from" to today, "to" to today, "project_id" to f.projectA)).expect(200)
        assertThat(report["seconds"].asLong()).isEqualTo(30 * 60L)
        f.admin.post("/api/v1/time_entries/${timer.id()}/stop").expect(200)
    }

    @Test
    fun `people see only the time they may see, and amounts only with rate visibility`() {
        val f = fixture()
        val mine = f.member.get("/api/v1/reports/time", september + ("group_by" to "client")).expect(200)
        assertThat(mine["seconds"].asLong()).isEqualTo(7200)
        assertThat(mine["rows"].map { it["name"].asText() }).containsExactly("Maple Labs")
        assertThat(mine.raw).doesNotContain("billable_amount").doesNotContain("cost_amount")

        val manager = invite(f.admin, role = "manager", name = "Mia Manager")
        f.admin.post("/api/v1/projects/${f.projectA}/members", mapOf("membership_id" to manager.membershipId, "is_manager" to true)).expect(201)
        val managed = manager.get("/api/v1/reports/time", september + ("group_by" to "project")).expect(200)
        assertThat(managed["rows"].map { it["id"].asText() }).containsExactly(f.projectA.toString())
        assertThat(managed["seconds"].asLong()).isEqualTo(6300)

        val detailed = f.member.get("/api/v1/reports/detailed", september).expect(200)
        assertThat(detailed["data"].map { it["id"].asText() }).containsExactly(f.memberDev.toString())
    }

    @Test
    fun `the detailed report pages entries oldest first, and bulk edit changes the unlocked ones`() {
        val f = fixture()
        val page1 = f.admin.get("/api/v1/reports/detailed", september + ("limit" to 2)).expect(200)
        assertThat(page1["data"].map { it["id"].asText() }).containsExactly(f.adminDesign.toString(), f.adminDev.toString())
        val page2 = f.admin.get("/api/v1/reports/detailed", september + mapOf("limit" to 2, "cursor" to page1["next_cursor"].asText())).expect(200)
        assertThat(page2["data"].map { it["id"].asText() }).containsExactly(f.memberDev.toString())
        assertThat(page2["next_cursor"].isNull).isTrue()

        // Invoice Maple Labs, which locks Mo's entry.
        f.admin.post("/api/v1/invoices", mapOf("client_id" to f.usdClient, "from_time" to september)).expect(201)
        val stranger = UUID.randomUUID()
        val result = f.admin.post(
            "/api/v1/time_entries/bulk",
            mapOf("ids" to listOf(f.adminDesign, f.adminDev, f.memberDev, stranger), "billable" to false),
        ).expect(200)
        assertThat(result["updated"].asInt()).isEqualTo(2)
        assertThat(result["skipped"].map { it["id"].asText() to it["code"].asText() })
            .containsExactlyInAnyOrder(f.memberDev.toString() to "invoiced", stranger.toString() to "not_found")
        assertThat(f.admin.get("/api/v1/time_entries/${f.adminDesign}")["billable"].asBoolean()).isFalse()

        // Move both to Maple Labs / Development: Ada is on that project as its creator.
        val moved = f.admin.post("/api/v1/time_entries/bulk", mapOf("ids" to listOf(f.adminDesign, f.adminDev), "project_id" to f.projectB, "task_id" to f.dev)).expect(200)
        assertThat(moved["updated"].asInt()).isEqualTo(2)
        val entry = f.admin.get("/api/v1/time_entries/${f.adminDesign}").expect(200)
        assertThat(entry["project"]["id"].asText()).isEqualTo(f.projectB.toString())
        // Moving re-applies the new project's rate and billable default.
        assertThat(entry["billable_rate"].asLong()).isEqualTo(8_000)

        // A task that isn't on the project skips the entry instead of failing the batch.
        val wrongTask = f.admin.post("/api/v1/time_entries/bulk", mapOf("ids" to listOf(f.adminDev), "project_id" to f.projectB, "task_id" to f.design)).expect(200)
        assertThat(wrongTask["skipped"].single()["code"].asText()).isEqualTo("validation_failed")

        // Members can't reach other people's entries.
        val theirs = f.member.post("/api/v1/time_entries/bulk", mapOf("ids" to listOf(f.adminDev), "billable" to true)).expect(200)
        assertThat(theirs["skipped"].single()["code"].asText()).isEqualTo("not_found")

        f.admin.post("/api/v1/time_entries/bulk", mapOf("ids" to emptyList<UUID>(), "billable" to true)).expectError(422, "validation_failed")
        f.admin.post("/api/v1/time_entries/bulk", mapOf("ids" to listOf(f.adminDev))).expectError(422, "validation_failed")
        f.admin.post("/api/v1/time_entries/bulk", mapOf("ids" to listOf(f.adminDev), "project_id" to f.projectA)).expectError(422, "validation_failed")
    }

    @Test
    fun `the uninvoiced report shows what invoicing would pick up, per client and project`() {
        val f = fixture()
        val travel = f.admin.post("/api/v1/expense_categories", mapOf("name" to "Travel")).expect(201).id()
        f.admin.post("/api/v1/expenses", mapOf("project_id" to f.projectA, "category_id" to travel, "spent_date" to "2026-09-10", "amount" to 4_250)).expect(201)

        val report = f.admin.get("/api/v1/reports/uninvoiced", september).expect(200)
        assertThat(report["clients"].map { it["client"]["name"].asText() }).containsExactly("Fjord & Pine", "Maple Labs")
        val fjord = report["clients"][0]
        assertThat(fjord["currency"].asText()).isEqualTo("EUR")
        // Only billable time counts: the non-billable half hour is not waiting to be invoiced.
        assertThat(fjord["seconds"].asLong()).isEqualTo(4500)
        assertThat(fjord["amount"].asLong()).isEqualTo(12_500)
        assertThat(fjord["expense_amount"].asLong()).isEqualTo(4_250)
        assertThat(fjord["oldest_date"].asText()).isEqualTo("2026-09-10")
        assertThat(fjord["projects"].single()["entry_count"].asInt()).isEqualTo(1)
        assertThat(report["totals"].map { it["currency"].asText() to it["amount"].asLong() }).containsExactly("EUR" to 12_500L, "USD" to 16_000L)

        f.admin.post("/api/v1/invoices", mapOf("client_id" to f.eurClient, "from_time" to september + ("include_expenses" to true))).expect(201)
        val after = f.admin.get("/api/v1/reports/uninvoiced", september).expect(200)
        assertThat(after["clients"].map { it["client"]["name"].asText() }).containsExactly("Maple Labs")

        // Members see their own uninvoiced hours, without amounts.
        val mine = f.member.get("/api/v1/reports/uninvoiced", september).expect(200)
        assertThat(mine["clients"].single()["seconds"].asLong()).isEqualTo(7200)
        assertThat(mine.raw).doesNotContain("\"amount\"")
    }

    @Test
    fun `the budget report lists projects with budgets the caller may see`() {
        val f = fixture()
        f.admin.patch("/api/v1/projects/${f.projectA}", mapOf("budget_by" to "project", "budget_seconds" to 4 * 3600, "show_budget_to_all" to true)).expect(200)
        f.admin.patch("/api/v1/projects/${f.projectB}", mapOf("budget_by" to "project_cost", "budget_amount" to 10_000)).expect(200)
        createProject(f.admin)

        val report = f.admin.get("/api/v1/reports/budget").expect(200)
        assertThat(report["rows"].map { it["project"]["id"].asText() }).containsExactly(f.projectA.toString(), f.projectB.toString())
        val a = report["rows"][0]["budget"]
        assertThat(a["spent_seconds"].asLong()).isEqualTo(6300)
        assertThat(a["percent_used"].asDouble()).isEqualTo(43.8)
        val b = report["rows"][1]["budget"]
        assertThat(b["is_over_budget"].asBoolean()).isTrue()
        assertThat(b["spent_amount"].asLong()).isEqualTo(16_000)

        // Mo is on both projects, but only the time budget is shown to everyone.
        val mine = f.member.get("/api/v1/reports/budget").expect(200)
        assertThat(mine["rows"].map { it["project"]["id"].asText() }).containsExactly(f.projectA.toString())
        assertThat(mine.raw).doesNotContain("spent_amount")

        assertThat(f.admin.get("/api/v1/reports/budget", mapOf("client_id" to f.usdClient))["rows"]).hasSize(1)
    }

    @Test
    fun `the expense report groups by category, client, project or person`() {
        val f = fixture()
        val travel = f.admin.post("/api/v1/expense_categories", mapOf("name" to "Travel")).expect(201).id()
        val meals = f.admin.post("/api/v1/expense_categories", mapOf("name" to "Meals")).expect(201).id()
        f.admin.post("/api/v1/expenses", mapOf("project_id" to f.projectA, "category_id" to travel, "spent_date" to "2026-09-10", "amount" to 4_250)).expect(201)
        f.admin.post("/api/v1/expenses", mapOf("project_id" to f.projectA, "category_id" to meals, "spent_date" to "2026-09-11", "amount" to 1_800, "billable" to false)).expect(201)
        f.member.post("/api/v1/expenses", mapOf("project_id" to f.projectB, "category_id" to travel, "spent_date" to "2026-09-12", "amount" to 9_900)).expect(201)

        val byCategory = f.admin.get("/api/v1/reports/expenses", september + ("group_by" to "category")).expect(200)
        assertThat(byCategory["rows"].map { it["name"].asText() }).containsExactly("Meals", "Travel")
        val travelRow = byCategory.row("Travel")
        assertThat(travelRow["count"].asInt()).isEqualTo(2)
        assertThat(travelRow["amounts"].map { it["currency"].asText() to it["amount"].asLong() }).containsExactly("EUR" to 4_250L, "USD" to 9_900L)
        val meal = byCategory.row("Meals")["amounts"].single()
        assertThat(meal["billable_amount"].asLong()).isEqualTo(0)
        assertThat(byCategory["count"].asInt()).isEqualTo(3)

        val byPerson = f.admin.get("/api/v1/reports/expenses", september + ("group_by" to "person")).expect(200)
        assertThat(byPerson["rows"].map { it["name"].asText() }).containsExactly("Ada Admin", "Mo Member")
        assertThat(f.admin.get("/api/v1/reports/expenses", september + mapOf("group_by" to "project", "billable" to true))["count"].asInt()).isEqualTo(2)
        // Members see their own spend, amounts included (ADR 0008).
        val mine = f.member.get("/api/v1/reports/expenses", september).expect(200)
        assertThat(mine["count"].asInt()).isEqualTo(1)
        assertThat(mine["amounts"].single()["amount"].asLong()).isEqualTo(9_900)
    }

    @Test
    fun `reports export as CSV that spreadsheets open safely, and as typed XLSX`() {
        val f = fixture()
        val csv = f.admin.get("/api/v1/reports/time/export", september + mapOf("format" to "csv", "group_by" to "client")).expect(200)
        assertThat(csv.headers["Content-Type"]!!.single()).startsWith("text/csv")
        assertThat(csv.headers["Content-Disposition"]!!.single()).contains("time-by-client-2026-09-01-to-2026-09-30.csv")
        val text = csv.bytes.toString(Charsets.UTF_8)
        assertThat(text).startsWith("﻿")
        val lines = text.removePrefix("﻿").trimEnd().split("\r\n")
        assertThat(lines[0]).isEqualTo(
            "Client,Hours,Billable hours,Billable %,Billable amount (EUR),Uninvoiced amount (EUR),Billable amount (USD),Uninvoiced amount (USD),Cost amount (EUR)",
        )
        assertThat(lines[1]).isEqualTo("Fjord & Pine,1.75,1.25,71.4,125.00,125.00,0.00,0.00,0.00")
        assertThat(lines[2]).isEqualTo("Maple Labs,2.00,2.00,100.0,0.00,0.00,160.00,160.00,80.00")
        assertThat(lines[3]).isEqualTo("Total,3.75,3.25,86.7,125.00,125.00,160.00,160.00,80.00")

        // Notes that look like formulas are defused; commas are quoted.
        val detailed = f.admin.get("/api/v1/reports/detailed/export", september + ("format" to "csv")).expect(200).bytes.toString(Charsets.UTF_8)
        assertThat(detailed).contains(",\"'=1+2, logo\",1.17,1.25,Yes,No,Not submitted,Ada Admin,EUR,100.00,125.00,")
        assertThat(detailed.lines()[0]).contains("Billable rate").contains("Cost rate (EUR)")

        // Without rate visibility, the money columns are left out.
        val memberExport = f.member.get("/api/v1/reports/detailed/export", september + ("format" to "csv")).expect(200).bytes.toString(Charsets.UTF_8)
        assertThat(memberExport.lines()[0]).doesNotContain("rate").doesNotContain("amount").doesNotContain("Currency")
        assertThat(memberExport.trimEnd().lines()).hasSize(2)

        val xlsx = f.admin.get("/api/v1/reports/time/export", september + mapOf("format" to "xlsx", "group_by" to "project")).expect(200)
        assertThat(xlsx.headers["Content-Type"]!!.single()).contains("spreadsheetml")
        // REPORT_SAMPLES=1 keeps the file for a look in a spreadsheet app.
        if (System.getenv("REPORT_SAMPLES") != null) java.io.File("build/report-samples").apply { mkdirs() }.resolve("time-by-project.xlsx").writeBytes(xlsx.bytes)
        val parts = unzip(xlsx.bytes)
        val strings = parts["xl/sharedStrings.xml"].orEmpty()
        val sheet = parts["xl/worksheets/sheet1.xml"]!!
        assertThat(strings).contains("Fjord &amp; Pine").contains("Billable amount (EUR)")
        assertThat(sheet).contains("<v>1.75</v>").contains("<v>125.00</v>").contains("<pane")

        for (kind in listOf("uninvoiced", "budget", "expenses", "expense_items")) {
            for (format in listOf("csv", "xlsx")) f.admin.get("/api/v1/reports/$kind/export", september + ("format" to format)).expect(200)
        }
        f.admin.get("/api/v1/reports/time/export", mapOf("format" to "pdf")).expectError(422, "validation_failed")
        f.admin.get("/api/v1/reports/salaries/export").expectError(422, "validation_failed")
        f.admin.get("/api/v1/reports/expenses/export", mapOf("group_by" to "task")).expectError(422, "validation_failed")
    }

    @Test
    fun `CSV cells are quoted and guarded`() {
        assertThat(Csv.escape("plain")).isEqualTo("plain")
        assertThat(Csv.escape("a,b")).isEqualTo("\"a,b\"")
        assertThat(Csv.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(Csv.escape("two\nlines")).isEqualTo("\"two\nlines\"")
        assertThat(Csv.escape(" padded")).isEqualTo("\" padded\"")
        for (s in listOf("=SUM(A1)", "+1", "-1", "@cmd", "\tx")) assertThat(Csv.guard(s)).startsWith("'")
        assertThat(Csv.guard("Design")).isEqualTo("Design")
        assertThat(Csv.cell(ColumnType.HOURS, 5400L)).isEqualTo("1.50")
        assertThat(Csv.cell(ColumnType.NUMBER, -3)).isEqualTo("-3")
    }

    /** Reads the zip through its central directory, as spreadsheet apps do (entries stream without sizes). */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val file = java.io.File.createTempFile("report", ".xlsx").apply { deleteOnExit(); writeBytes(bytes) }
        return ZipFile(file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) } }
    }
}
