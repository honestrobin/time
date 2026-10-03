// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.support.IntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * AT-1.5: a member without rate visibility never receives rate or amount fields from any API
 * endpoint. Crawls every GET endpoint (filling path variables from a fixture), plus the
 * mutations a member can make, and scans every JSON key.
 */
class RateVisibilityTest : IntegrationTest() {
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    lateinit var mappings: RequestMappingHandlerMapping

    private val sensitive = Regex("(^|_)(rate|rates|amount|cost|fee|price|total_amount)(_|$)")

    /** Expense amounts are the person's own spend, not billing data (ADR 0008), in the expense report too. */
    private val allowed = setOf("expenses.amount", "reports/expenses.amount", "reports/expenses.billable_amount", "reports/expenses.uninvoiced_amount")

    /** Permission flags, not data. */
    private val allowedKeys = setOf("can_see_rates")

    @Test
    fun `members never see rates or amounts`() {
        val admin = signup()
        val member = invite(admin, extra = mapOf("default_billable_rate" to 9_000, "cost_rate" to 4_000))
        val task = createTask(admin, defaultRate = 7_000)
        val clientId = createClient(admin)
        val project = createProject(
            admin, clientId = clientId, taskIds = listOf(task), members = listOf(member),
            extra = mapOf("bill_by" to "people", "hourly_rate" to 10_000, "budget_by" to "project", "budget_seconds" to 36_000, "show_budget_to_all" to true),
        ).id()
        val category = admin.post("/api/v1/expense_categories", mapOf("name" to "Mileage", "unit_name" to "km", "unit_price" to 30)).expect(201).id()
        val entry = member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 3600)).expect(201)
        scan("POST /api/v1/time_entries", entry.body)
        val expense = member.post("/api/v1/expenses", mapOf("project_id" to project, "category_id" to category, "spent_date" to "2026-09-15", "units" to 12)).expect(201)
        scan("POST /api/v1/expenses", expense.body, "expenses")
        scan("PATCH /api/v1/time_entries", member.patch("/api/v1/time_entries/${entry.id()}", mapOf("notes" to "hi")).expect(200).body)
        scan("POST start", member.post("/api/v1/time_entries/${entry.id()}/start").expect(200).body)
        scan("POST stop", member.post("/api/v1/time_entries/${entry.id()}/stop").expect(200).body)
        scan("PUT cell", member.put("/api/v1/timesheets/week/cell", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-16", "duration_seconds" to 1800)).expect(200).body)

        val ids = mapOf(
            "id" to listOf(project, entry.id(), expense.id(), clientId, task, member.membershipId, category),
            "contactId" to emptyList(),
        )
        val gets = mappings.handlerMethods.keys
            .filter { RequestMethod.GET in it.methodsCondition.methods }
            .flatMap { it.pathPatternsCondition!!.patternValues }
            .filter { it.startsWith("/api/v1/") && !it.startsWith("/api/v1/auth/") }
            .distinct()
        assertThat(gets).contains("/api/v1/time_entries", "/api/v1/projects/{id}/budget", "/api/v1/timesheets/week")

        var checked = 0
        for (pattern in gets) {
            val vars = Regex("\\{(\\w+)}").findAll(pattern).map { it.groupValues[1] }.toList()
            val candidates = if (vars.isEmpty()) listOf(pattern) else ids[vars.single()].orEmpty().map { pattern.replace("{${vars.single()}}", it.toString()) }
            for (path in candidates) {
                val res = member.get(path, mapOf("from" to "2026-09-01", "to" to "2026-09-30", "start" to "2026-09-14"))
                if (res.status == 200 && !res.body.isMissingNode) {
                    val resource = pattern.removePrefix("/api/v1/").let { if (it.startsWith("reports/")) it else it.substringBefore('/') }
                    scan("GET $path", res.body, resource)
                    checked++
                }
            }
        }
        assertThat(checked).isGreaterThan(8)

        // The admin, by contrast, does see them.
        assertThat(admin.get("/api/v1/time_entries/${entry.id()}").expect(200).body.has("billable_rate")).isTrue()
    }

    private fun scan(where: String, node: JsonNode, resource: String = "", path: String = "") {
        when {
            node.isObject -> node.fields().forEach { (k, v) ->
                val qualified = "$resource.$k"
                if (sensitive.containsMatchIn(k) && qualified !in allowed && k !in allowedKeys) {
                    throw AssertionError("$where exposes '$k' at $path to a member without rate visibility: $node")
                }
                scan(where, v, resource, "$path.$k")
            }
            node.isArray -> node.forEach { scan(where, it, resource, "$path[]") }
        }
    }
}
