// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.notifications

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * "No noise" in the Robin's Code: no emails that exist only to bring you back to the product.
 * Every kind of mail Time sends is a template, listed below with why someone wants it, and the
 * reminders and alerts start off. The test fails if the promise breaks by accident; it can't
 * stop it being broken on purpose.
 */
class NoMailToBringYouBackTest : IntegrationTest() {
    @Test
    fun `no mail is sent to bring people back, and reminders start off`() {
        // Every mail is one of these templates, and none is there to bring anyone back. A new one
        // fails this test until it's listed here with its reason.
        val templates = File("src/main/resources/templates/mail").listFiles().orEmpty().filter { it.isFile }.map { it.nameWithoutExtension }.toSet() - "layout"
        assertThat(templates).describedAs("mail templates").containsExactlyInAnyOrderElementsOf(MAIL.keys)
        // Only the mailer and invoice sending put a mail together themselves, and invoice sending
        // uses the invoice templates above.
        val root = File("src/main/kotlin")
        val builders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && "OutgoingMail(" in it.readText() }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertThat(builders).describedAs("main code that builds a mail").containsExactlyInAnyOrder(
            "com/honestrobin/time/platform/mail/Mailer.kt",
            "com/honestrobin/time/invoicing/InvoiceSending.kt",
        )

        // A new account sends no timesheet or invoice reminders until someone turns them on, and
        // neither does a second workspace.
        val admin = signup(accountName = "Matai Studio")
        val second = UUID.fromString(
            admin.post("/api/v1/accounts", mapOf("name" to "Matai Two", "timezone" to "Europe/Zagreb", "default_currency" to "EUR")).expect(201)["id"].asText(),
        )
        val first = admin.accountId!!
        for (account in listOf(first, second)) {
            admin.accountId = account
            assertThat(admin.get("/api/v1/account").expect(200)["timesheet_reminders_enabled"].asBoolean()).describedAs("timesheet reminders").isFalse()
            assertThat(admin.get("/api/v1/invoice_settings").expect(200)["reminders_enabled"].asBoolean()).describedAs("invoice reminders").isFalse()
        }
        admin.accountId = first

        // A project with a budget sends no alert, even over budget, until someone turns it on.
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), extra = mapOf("budget_by" to "project", "budget_seconds" to 3600, "budget_alert_percent" to 80))
        assertThat(project["notify_when_over_budget"].asBoolean()).isFalse()
        fun track(hours: Int) = admin.post(
            "/api/v1/time_entries",
            mapOf("project_id" to project.id(), "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to hours * 3600),
        ).expect(201)
        fun alerts() = mail.to(admin.email!!).filter { it.template == "budget-alert" }
        track(2)
        assertThat(alerts()).isEmpty()
        // Turned on, it works: so "off" above is the setting, not a broken alert.
        admin.patch("/api/v1/projects/${project.id()}", mapOf("notify_when_over_budget" to true)).expect(200)
        track(1)
        assertThat(alerts()).isNotEmpty()
    }
}

/** Every kind of mail Time sends, and why someone wants it. */
private val MAIL = mapOf(
    "magic-link" to "someone asked to sign in with a link",
    "password-reset" to "someone asked to reset their password",
    "verify-email" to "confirms the address someone signed up with, or asked to confirm again",
    "invite" to "an admin invited someone into the account",
    "security-notice" to "something changed in how someone signs in (a new device, two-factor, a password), so they notice what they didn't do",
    "export-ready" to "the export someone asked for is ready",
    "account-deletion" to "an admin asked to delete the account: every admin hears, with the date",
    "account-deletion-cancelled" to "an admin took the deletion back",
    "invoice" to "an invoice someone sends their client",
    "invoice-reminder" to "a client's unpaid invoice: off until someone turns invoice reminders on",
    "invoice-overdue" to "a client's overdue invoice: off until someone turns invoice reminders on",
    "timesheet-reminder" to "last week's timesheet isn't complete: off until an admin turns timesheet reminders on",
    "timesheet-rejected" to "an approver sent a timesheet back",
    "budget-alert" to "a project reached its budget alert: off until someone turns it on for the project",
    "price-above-lock" to "Paddle reported a price above the subscription's locked one",
)
