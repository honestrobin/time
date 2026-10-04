// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers

import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.EXPENSE_CATEGORIES
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.INVOICE_SEQUENCES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PAYMENTS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TEAMS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.support.HarvestFixture
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockHarvest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Table
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Spec §6 and acceptance tests AT-2.1, AT-2.2, AT-2.3, AT-2.5 and AT-2.6, against MockHarvest. */
class HarvestImportTest : IntegrationTest() {

    private fun start(admin: TestClient) =
        admin.post("/api/v1/imports/harvest", mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID)).expect(201)

    private fun count(table: Table<*>, account: UUID): Int = tx.system {
        dsl.fetchCount(table, table.field("account_id", UUID::class.java)!!.eq(account))
    }

    private fun importedCount(account: UUID, type: String): Int = tx.system {
        dsl.fetchCount(com.honestrobin.time.db.Tables.EXTERNAL_LINKS,
            com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ACCOUNT_ID.eq(account).and(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_TYPE.eq(type)))
    }

    @Test
    fun `a whole agency comes over, and every total matches Harvest's (AT-2_1)`() {
        val f = HarvestFixture.agency(seed = 11, people = 6, clients = 4, projectsPerClient = 3, weeks = 20, receiptBaseUrl = MockHarvest.receiptUrl)
        MockHarvest.reset(f)
        val admin = signup(timezone = "America/New_York", currency = "USD")
        val account = admin.accountId!!

        val job = start(admin)
        assertThat(job["status"].asText()).describedAs(job.raw).isEqualTo("completed")
        assertThat(job["error"].isNull).isTrue()

        // Every record arrived exactly once (the admin signed up separately and is not a Harvest user).
        assertThat(count(CLIENTS, account)).isEqualTo(f.clients.size)
        assertThat(count(CLIENT_CONTACTS, account)).isEqualTo(f.contacts.size)
        assertThat(count(TASKS, account)).isEqualTo(f.tasks.size)
        assertThat(count(PROJECTS, account)).isEqualTo(f.projects.size)
        assertThat(count(PROJECT_TASKS, account)).isEqualTo(f.taskAssignments.size)
        assertThat(count(PROJECT_MEMBERS, account)).isEqualTo(f.userAssignments.size)
        assertThat(count(MEMBERSHIPS, account)).isEqualTo(f.users.size + 1)
        assertThat(count(TEAMS, account)).isEqualTo(f.roles.size)
        assertThat(count(EXPENSE_CATEGORIES, account)).isEqualTo(f.expenseCategories.size)
        assertThat(count(TIME_ENTRIES, account)).isEqualTo(f.timeEntries.size)
        assertThat(count(EXPENSES, account)).isEqualTo(f.expenses.size)
        assertThat(count(INVOICES, account)).isEqualTo(f.invoices.size)
        assertThat(count(PAYMENTS, account)).isEqualTo(f.payments.values.sumOf { it.size })

        val verification = job["verification"]
        assertThat(verification["all_match"].asBoolean()).describedAs(verification.toString()).isTrue()
        assertThat(verification["entries"].asInt()).isEqualTo(f.timeEntries.size)
        assertThat(verification["rows"].values().map { it["status"].asText() }).isNotEmpty().allMatch { it == "match" || it == "rounding" }
        // Clients bill in four currencies, including one with no decimals.
        assertThat(verification["rows"].values().map { it["currency"].asText() }.toSet()).contains("USD", "EUR", "GBP", "JPY")

        // People are added but not invited; nobody gets an email until an admin chooses.
        val people = admin.get("/api/v1/people").expect(200)
        val imported = people["data"]
        assertThat(imported.filter { it["email"].asText().endsWith("@fixture.example") }).isNotEmpty().allMatch { it["status"].asText() == "pending_invite" }
        assertThat(mail.sent.filter { it.to.any { to -> to.endsWith("@fixture.example") } }).isEmpty()

        // Spot checks on the rules in spec §6.4.
        tx.system {
            val running = f.timeEntries.firstOrNull { it["is_running"] == true }
            if (running != null) {
                val issues = admin.get("/api/v1/imports/${job.id()}/issues").expect(200)
                assertThat(issues.body.values().map { it["reason"].asText() }).anyMatch { it.contains("running in Harvest") }
            }
            val locked = dsl.fetchCount(TIME_ENTRIES, TIME_ENTRIES.ACCOUNT_ID.eq(account).and(TIME_ENTRIES.IS_LOCKED.isTrue))
            assertThat(locked).isEqualTo(f.timeEntries.count { it["is_locked"] == true || it["is_billed"] == true })
            val onInvoices = dsl.fetchCount(TIME_ENTRIES, TIME_ENTRIES.ACCOUNT_ID.eq(account).and(TIME_ENTRIES.INVOICE_ID.isNotNull))
            assertThat(onInvoices).isEqualTo(f.timeEntries.count { it["invoice"] != null })
            val withReceipts = dsl.fetchCount(EXPENSES, EXPENSES.ACCOUNT_ID.eq(account).and(EXPENSES.RECEIPT_FILE_ID.isNotNull))
            assertThat(withReceipts).isEqualTo(f.receipts.size)
            val archived = dsl.fetchCount(CLIENTS, CLIENTS.ACCOUNT_ID.eq(account).and(CLIENTS.IS_ACTIVE.isFalse))
            assertThat(archived).isEqualTo(f.clients.count { it["is_active"] == false })
            val yen = dsl.select(INVOICES.TOTAL_MINOR).from(INVOICES).join(CLIENTS).on(CLIENTS.ID.eq(INVOICES.CLIENT_ID))
                .where(INVOICES.ACCOUNT_ID.eq(account)).and(CLIENTS.CURRENCY.eq("JPY")).limit(1).fetchOne()?.value1()
            val yenInvoice = f.invoices.firstOrNull { it["currency"] == "JPY" }
            if (yenInvoice != null) assertThat(yen).isEqualTo((yenInvoice["amount"] as BigDecimal).toLong())
        }
    }

    @Test
    fun `running the import again changes what changed and duplicates nothing (AT-2_2)`() {
        val f = HarvestFixture.agency(seed = 12, people = 4, clients = 2, projectsPerClient = 2, weeks = 16)
        MockHarvest.reset(f)
        val admin = signup()
        val account = admin.accountId!!
        assertThat(start(admin)["status"].asText()).isEqualTo("completed")
        val entriesBefore = count(TIME_ENTRIES, account)

        // In Harvest meanwhile: a client is renamed, an entry is corrected, and an entry is added.
        f.clients[0]["name"] = "Renamed Client"
        val corrected = f.timeEntries.first { it["is_locked"] == false }
        corrected["hours"] = BigDecimal("7.75")
        f.timeEntries += HashMap(corrected).apply { put("id", 999_999L); put("hours", BigDecimal("1.00")); put("notes", "Added later") }

        val second = start(admin)
        assertThat(second["status"].asText()).isEqualTo("completed")
        assertThat(second["verification"]["all_match"].asBoolean()).isTrue()
        assertThat(count(CLIENTS, account)).isEqualTo(f.clients.size)
        assertThat(count(TIME_ENTRIES, account)).isEqualTo(entriesBefore + 1)
        assertThat(count(PROJECTS, account)).isEqualTo(f.projects.size)
        assertThat(count(INVOICES, account)).isEqualTo(f.invoices.size)
        tx.system {
            assertThat(dsl.fetchExists(CLIENTS, CLIENTS.ACCOUNT_ID.eq(account).and(CLIENTS.NAME.eq("Renamed Client")))).isTrue()
            val seconds = dsl.select(TIME_ENTRIES.DURATION_SECONDS).from(TIME_ENTRIES).where(TIME_ENTRIES.ACCOUNT_ID.eq(account)).and(TIME_ENTRIES.NOTES.eq(corrected["notes"] as String))
                .fetch(TIME_ENTRIES.DURATION_SECONDS)
            assertThat(seconds).contains(27_900)
        }
    }

    @Test
    fun `throttling is waited out, and a run that stops resumes from its last page (AT-2_3)`() {
        val f = HarvestFixture.agency(seed = 13, people = 4, clients = 2, projectsPerClient = 2, weeks = 20)
        MockHarvest.reset(f)
        val older = f.timeEntries.count { java.time.LocalDate.parse(it["spent_date"] as String).isBefore(java.time.LocalDate.now().minusDays(90)) }
        assertThat(older).describedAs("the fixture needs two pages of older entries").isGreaterThan(50)
        MockHarvest.throttleEvery = 4
        // Harvest keeps failing on the second page of older time entries, after the first was imported.
        MockHarvest.failWhen = Regex("/time_entries\\?.*to=.*page=2")
        val admin = signup()
        val account = admin.accountId!!

        val stopped = start(admin)
        assertThat(stopped["status"].asText()).isEqualTo("failed")
        assertThat(stopped["can_resume"].asBoolean()).isTrue()
        assertThat(stopped["error"].asText()).contains("Resume")
        assertThat(MockHarvest.throttled.get()).isPositive()
        val partial = count(TIME_ENTRIES, account)
        assertThat(partial).isLessThan(f.timeEntries.size)

        MockHarvest.failWhen = null
        val resumed = admin.post("/api/v1/imports/${stopped.id()}/resume").expect(200)
        assertThat(resumed["status"].asText()).describedAs(resumed.raw).isEqualTo("completed")
        assertThat(count(TIME_ENTRIES, account)).isEqualTo(f.timeEntries.size)
        assertThat(importedCount(account, "time_entry")).isEqualTo(f.timeEntries.size)
        assertThat(resumed["verification"]["all_match"].asBoolean()).isTrue()
        // The token is deleted once nothing can resume any more.
        admin.post("/api/v1/imports/${stopped.id()}/resume").expectError(409, "not_resumable")
    }

    @Test
    fun `imported invoices are read-only and numbering continues after Harvest's highest number (AT-2_5)`() {
        val f = HarvestFixture.agency(seed = 14, people = 3, clients = 3, projectsPerClient = 1, weeks = 16)
        MockHarvest.reset(f)
        val admin = signup()
        val account = admin.accountId!!
        assertThat(start(admin)["status"].asText()).isEqualTo("completed")

        val highest = f.invoices.mapNotNull { (it["number"] as String?)?.removePrefix("INV-")?.toLong() }.max()
        tx.system {
            assertThat(dsl.fetchCount(INVOICES, INVOICES.ACCOUNT_ID.eq(account).and(INVOICES.IS_READ_ONLY.isFalse))).isZero()
            assertThat(dsl.fetchCount(INVOICES, INVOICES.ACCOUNT_ID.eq(account).and(INVOICES.SOURCE.ne("harvest_import")))).isZero()
            val sequence = dsl.selectFrom(INVOICE_SEQUENCES).where(INVOICE_SEQUENCES.ACCOUNT_ID.eq(account)).and(INVOICE_SEQUENCES.IS_DEFAULT.isTrue).fetchOne()!!
            assertThat(sequence.nextNumber).isEqualTo(highest + 1)
            assertThat(sequence.prefix).isEqualTo("INV-")
            assertThat(sequence.padding.toInt()).isEqualTo(4)
            // A paid invoice keeps its payment, an open one its partial payment, a closed one becomes void.
            val states = dsl.select(INVOICES.STATE).from(INVOICES).where(INVOICES.ACCOUNT_ID.eq(account)).fetch(INVOICES.STATE)
            assertThat(states).contains("paid", "partially_paid", "void")
        }
    }

    @Test
    fun `the structure of a 25-person, 200-project agency imports within a minute at 150 ms per request (AT-2_6)`() {
        val f = HarvestFixture.agency(seed = 15, people = 25, clients = 20, projectsPerClient = 10, weeks = 0, invoicesPerClient = 0)
        MockHarvest.reset(f)
        MockHarvest.latencyMs = 150
        val admin = signup()
        val started = System.nanoTime()
        val job = start(admin)
        val seconds = (System.nanoTime() - started) / 1e9
        assertThat(job["status"].asText()).isEqualTo("completed")
        assertThat(count(PROJECTS, admin.accountId!!)).isEqualTo(200)
        assertThat(seconds).describedAs("import took $seconds s").isLessThan(60.0)
    }

    @Test
    fun `only admins import, and a token Harvest refuses is reported straight away`() {
        MockHarvest.reset(HarvestFixture.agency(seed = 16, people = 2, clients = 1, projectsPerClient = 1, weeks = 1))
        val admin = signup()
        val member = invite(admin)
        member.post("/api/v1/imports/harvest", mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID)).expectError(403, "forbidden")
        val bad = admin.post("/api/v1/imports/harvest", mapOf("token" to "wrong", "account_id" to MockHarvest.ACCOUNT_ID))
        bad.expectError(422, "validation_failed")
        assertThat(bad["fields"]["token"].asText()).contains("did not accept")
        admin.post("/api/v1/imports/harvest", mapOf("token" to MockHarvest.TOKEN, "account_id" to "not-a-number")).expectError(422, "validation_failed")
    }
}
