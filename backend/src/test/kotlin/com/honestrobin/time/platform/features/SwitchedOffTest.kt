// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.features

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.importers.harvest.HarvestImporter
import com.honestrobin.time.invoicing.InvoiceReminderService
import com.honestrobin.time.notifications.TimesheetReminderService
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.support.HarvestFixture
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockHarvest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * An instance with the default switches: everything beyond the core is off. Other tests run with
 * every feature on (application-test.yml). A switched-off feature stops what it would do unasked,
 * because nobody could see it or turn it off: the lock on a week sent for approval, timesheet
 * reminders, budget alerts, invoice reminders and a Harvest sync. Approved time stays locked, and
 * an approved week can take new entries while approvals are off. Its data stays, and so does the
 * export; the API refuses sending a week for approval.
 */
@TestPropertySource(properties = ["honestrobin.features="])
class SwitchedOffTest : IntegrationTest() {
    @Autowired lateinit var features: Features

    @Autowired lateinit var timesheetReminders: TimesheetReminderService

    @Autowired lateinit var invoiceReminders: InvoiceReminderService

    @Autowired lateinit var importer: HarvestImporter

    @BeforeEach
    fun defaults() {
        assertThat(features.on).describedAs("this test needs the default switches").isEmpty()
    }

    @Test
    fun `the web app is told which features are switched on, and by default none are`() {
        val config = client().get("/api/v1/auth/config").expect(200)
        assertThat(config["features"].isArray).isTrue()
        assertThat(config["features"].size()).isZero()
    }

    @Test
    fun `no budget alert goes out while budgets are switched off`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(
            admin, taskIds = listOf(task),
            extra = mapOf("budget_by" to "project", "budget_seconds" to 10 * 3600, "budget_alert_percent" to 80, "notify_when_over_budget" to true),
        ).id()
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 11 * 3600)).expect(201)
        assertThat(mail.to(admin.email!!).filter { it.template == "budget-alert" }).isEmpty()
    }

    @Test
    fun `a week sent for approval is open again while approvals are switched off`() {
        val admin = signup()
        admin.patch("/api/v1/account", mapOf("approvals_enabled" to true)).expect(200)
        val member = invite(admin, name = "Max Member")
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), members = listOf(member)).id()
        val monday = LocalDate.of(2026, 9, 7)
        val entry = member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to monday.toString(), "duration_seconds" to 7200)).expect(201).id()
        // Sent for approval while approvals were on: an imported account, or the switch turned off since.
        tx.system {
            dsl.update(TIME_ENTRIES).set(TIME_ENTRIES.APPROVAL_STATE, "submitted").where(TIME_ENTRIES.ID.eq(entry)).execute()
            dsl.insertInto(TIMESHEET_SUBMISSIONS)
                .set(TIMESHEET_SUBMISSIONS.ACCOUNT_ID, admin.accountId).set(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID, member.membershipId)
                .set(TIMESHEET_SUBMISSIONS.WEEK_START_DATE, monday).set(TIMESHEET_SUBMISSIONS.STATE, "submitted").execute()
        }

        val week = member.get("/api/v1/timesheets/week", mapOf("start" to monday)).expect(200)
        assertThat(week["approvals_enabled"].asBoolean()).isFalse()
        assertThat(week["is_read_only"].asBoolean()).isFalse()
        member.patch("/api/v1/time_entries/$entry", mapOf("duration_seconds" to 3600)).expect(200)
        member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-08", "duration_seconds" to 60)).expect(201)
        // Nobody could approve it, so nothing new is sent for approval either.
        member.post("/api/v1/timesheets/week/submit", mapOf("start" to monday)).expectError(409, "approvals_disabled")
    }

    @Test
    fun `approved time stays locked while approvals are switched off`() {
        val admin = signup()
        admin.patch("/api/v1/account", mapOf("approvals_enabled" to true)).expect(200)
        val member = invite(admin, name = "Ana Approved")
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), members = listOf(member)).id()
        val monday = LocalDate.of(2026, 9, 7)
        val entry = member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to monday.toString(), "duration_seconds" to 7200)).expect(201).id()
        // Approved while approvals were on, as Approvals.approve leaves it.
        tx.system {
            dsl.update(TIME_ENTRIES).set(TIME_ENTRIES.APPROVAL_STATE, "approved").set(TIME_ENTRIES.IS_LOCKED, true)
                .set(TIME_ENTRIES.LOCKED_REASON, "approved").where(TIME_ENTRIES.ID.eq(entry)).execute()
            dsl.insertInto(TIMESHEET_SUBMISSIONS)
                .set(TIMESHEET_SUBMISSIONS.ACCOUNT_ID, admin.accountId).set(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID, member.membershipId)
                .set(TIMESHEET_SUBMISSIONS.WEEK_START_DATE, monday).set(TIMESHEET_SUBMISSIONS.STATE, "approved").execute()
        }

        member.patch("/api/v1/time_entries/$entry", mapOf("duration_seconds" to 3600)).expectError(409, "entry_locked")
        member.delete("/api/v1/time_entries/$entry").expectError(409, "entry_locked")
        admin.patch("/api/v1/time_entries/$entry", mapOf("duration_seconds" to 3600)).expectError(409, "entry_locked")
        // Only the approved time is locked: the week itself takes new entries while approvals are off.
        member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-08", "duration_seconds" to 60)).expect(201)
    }

    @Test
    fun `timesheet reminders don't count an unsubmitted week while approvals are switched off`() {
        val admin = signup()
        admin.patch("/api/v1/account", mapOf("timesheet_reminders_enabled" to true, "approvals_enabled" to true)).expect(200)
        val busy = invite(admin, extra = mapOf("weekly_capacity_seconds" to 3600))
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), members = listOf(busy)).id()
        busy.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 3600)).expect(201)

        val monday = ZonedDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneId.of("Europe/Zagreb"))
        val sent = DbContext.forAccount(admin.accountId!!) {
            tx.run { timesheetReminders.remindAccount(dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(admin.accountId)).fetchOne()!!, monday) }
        }
        assertThat(sent).isEqualTo(1) // the admin, who tracked nothing
        assertThat(mail.to(busy.email!!).filter { it.template == "timesheet-reminder" }).isEmpty()
    }

    @Test
    fun `no invoice reminder goes out while invoices are switched off`() {
        val admin = signup()
        val client = createClient(admin)
        val recipient = "billing-$client@client.example"
        admin.post("/api/v1/clients/$client/contacts", mapOf("name" to "Billing", "email" to recipient, "is_invoice_recipient" to true)).expect(201)
        admin.patch("/api/v1/invoice_settings", mapOf("reminders_enabled" to true, "reminder_days" to listOf(0))).expect(200)
        // Due today, so with invoices on a reminder would go out now, as in OutboundMailTest.
        val invoice = admin.post(
            "/api/v1/invoices",
            mapOf("client_id" to client, "payment_terms_days" to 0, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 100_000))),
        ).expect(201).id()
        admin.post("/api/v1/invoices/$invoice/send", mapOf<String, Any>()).expect(200)
        val before = mail.to(recipient).size

        assertThat(invoiceReminders.runDue()).isZero()
        assertThat(tx.run { DbContext.forAccount(admin.accountId!!) { invoiceReminders.remindAccount(admin.accountId!!, setOf(0)) } }).isZero()
        assertThat(mail.to(recipient)).hasSize(before)
    }

    @Test
    fun `a Harvest sync doesn't run while the import is switched off`() {
        MockHarvest.reset(HarvestFixture.agency(seed = 23, people = 1, clients = 1, projectsPerClient = 1, weeks = 1))
        val admin = signup()
        val job = admin.post(
            "/api/v1/imports/harvest",
            mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID, "sync_until" to LocalDate.now(clock).plusDays(1)),
        ).expect(201)
        assertThat(job["status"].asText()).isEqualTo("syncing")
        // Its window is over, so a sync would end it; with the import switched off, nothing runs.
        tx.system { dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.SYNC_UNTIL, Instant.now().minusSeconds(60)).where(IMPORT_JOBS.ID.eq(job.id())).execute() }
        importer.syncAll()
        assertThat(admin.get("/api/v1/imports/${job.id()}").expect(200)["status"].asText()).isEqualTo("syncing")
    }
}
