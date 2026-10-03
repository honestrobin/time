// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.expenses

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpensesTest : IntegrationTest() {

    @Test
    fun `free-amount and unit-priced expenses with receipts`() {
        val admin = signup()
        val member = invite(admin)
        val project = createProject(admin, members = listOf(member)).id()
        val travel = admin.post("/api/v1/expense_categories", mapOf("name" to "Travel")).expect(201).id()
        val mileage = admin.post("/api/v1/expense_categories", mapOf("name" to "Mileage", "unit_name" to "km", "unit_price" to 35)).expect(201).id()
        member.post("/api/v1/expense_categories", mapOf("name" to "Nope")).expectError(403, "forbidden")

        val ticket = member.post("/api/v1/expenses", mapOf("project_id" to project, "category_id" to travel, "spent_date" to "2026-09-15", "amount" to 4_250, "notes" to "Train")).expect(201)
        assertThat(ticket["amount"].asLong()).isEqualTo(4_250)
        assertThat(ticket["currency"].asText()).isEqualTo("EUR")
        assertThat(ticket["billable"].asBoolean()).isTrue()

        val drive = member.post("/api/v1/expenses", mapOf("project_id" to project, "category_id" to mileage, "spent_date" to "2026-09-15", "units" to 120.5)).expect(201)
        assertThat(drive["amount"].asLong()).isEqualTo(4_218) // 120.5 km × 0.35, half-up

        val pdf = "%PDF-1.4 receipt".toByteArray()
        member.upload("/api/v1/expenses/${ticket.id()}/receipt", "file", "ticket.pdf", "application/pdf", pdf).expect(200)
        val download = member.get("/api/v1/expenses/${ticket.id()}/receipt").expect(200)
        assertThat(download.bytes).isEqualTo(pdf)
        member.upload("/api/v1/expenses/${ticket.id()}/receipt", "file", "evil.html", "text/html", "<script>".toByteArray()).expectError(422, "validation_failed")

        val other = invite(admin)
        other.get("/api/v1/expenses/${ticket.id()}").expectError(404, "not_found")
        assertThat(admin.get("/api/v1/expenses", mapOf("project_id" to project)).expect(200)["data"]).hasSize(2)
    }
}
