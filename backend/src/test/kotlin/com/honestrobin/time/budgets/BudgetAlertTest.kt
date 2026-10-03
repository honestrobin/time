// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.budgets

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** AT-1.4: budget alert email fires once when crossing 80% and once at 100%. */
class BudgetAlertTest : IntegrationTest() {

    @Test
    fun `alerts fire once per threshold crossing`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(
            admin, taskIds = listOf(task),
            extra = mapOf("budget_by" to "project", "budget_seconds" to 10 * 3600, "budget_alert_percent" to 80, "notify_when_over_budget" to true),
        ).id()
        val email = admin.email!!
        fun alerts() = mail.to(email).filter { it.template == "budget-alert" }
        fun track(hours: Double) = admin.post(
            "/api/v1/time_entries",
            mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to (hours * 3600).toInt()),
        ).expect(201)

        track(7.0)
        assertThat(alerts()).isEmpty()
        track(1.5) // 85%
        assertThat(alerts()).hasSize(1)
        assertThat(alerts().last().subject).contains("80%")
        track(0.5) // 90%
        assertThat(alerts()).hasSize(1)
        track(2.0) // 110%
        assertThat(alerts()).hasSize(2)
        assertThat(alerts().last().subject).contains("100%")
        track(1.0)
        assertThat(alerts()).hasSize(2)

        val budget = admin.get("/api/v1/projects/$project/budget").expect(200)
        assertThat(budget["spent_seconds"].asLong()).isEqualTo(12 * 3600L)
        assertThat(budget["is_over_budget"].asBoolean()).isTrue()
        assertThat(budget["percent_used"].asDouble()).isEqualTo(120.0)
    }

    @Test
    fun `money budgets use billable amounts and dropping below re-arms the alert`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(
            admin, taskIds = listOf(task),
            extra = mapOf("bill_by" to "project", "hourly_rate" to 10_000, "budget_by" to "project_cost", "budget_amount" to 100_000, "notify_when_over_budget" to true),
        ).id()
        val e = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 11 * 3600)).expect(201)
        assertThat(mail.to(admin.email!!).filter { it.template == "budget-alert" }).hasSize(1)
        admin.delete("/api/v1/time_entries/${e.id()}").expect(204)
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-16", "duration_seconds" to 10 * 3600)).expect(201)
        assertThat(mail.to(admin.email!!).filter { it.template == "budget-alert" }).hasSize(2)
    }
}
