// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

typealias Json = MutableMap<String, Any?>

/**
 * A generated Harvest account in the shapes of the Harvest API v2 (the fields the importer reads,
 * as recorded in HarvestModels). Deterministic for a given seed, and relative to [today] so it
 * always spans both the "recent" and the "history" import phases.
 */
class HarvestFixture(
    val company: Json,
    val users: MutableList<Json>,
    val roles: MutableList<Json>,
    val clients: MutableList<Json>,
    val contacts: MutableList<Json>,
    val tasks: MutableList<Json>,
    val projects: MutableList<Json>,
    val taskAssignments: MutableList<Json>,
    val userAssignments: MutableList<Json>,
    val expenseCategories: MutableList<Json>,
    val timeEntries: MutableList<Json>,
    val expenses: MutableList<Json>,
    val invoices: MutableList<Json>,
    val payments: MutableMap<Long, MutableList<Json>>,
    val estimates: MutableList<Json>,
    val receipts: MutableMap<Long, ByteArray>,
) {
    fun projectOf(id: Long) = projects.first { it["id"] == id }

    companion object {
        private val currencies = listOf("USD", "EUR", "GBP", "JPY", "AUD")
        private val billBy = listOf("Project", "Tasks", "People", "none")
        private val budgetBy = listOf("project", "project_cost", "task", "task_fees", "person", "none")

        private fun money(value: Double, currency: String): BigDecimal =
            BigDecimal(value).setScale(if (currency == "JPY") 0 else 2, RoundingMode.HALF_UP)

        fun agency(
            seed: Int = 7,
            people: Int = 6,
            clients: Int = 4,
            projectsPerClient: Int = 3,
            weeks: Int = 20,
            invoicesPerClient: Int = 3,
            today: LocalDate = LocalDate.now(),
            receiptBaseUrl: String = "",
        ): HarvestFixture {
            val rnd = Random(seed)
            var nextId = 10_000L
            fun id() = nextId++

            val company: Json = mutableMapOf(
                "name" to "Fixture Agency", "week_start_day" to "Monday", "time_format" to "hours_minutes", "clock" to "12h",
                "weekly_capacity" to 126_000, "approval_feature" to true, "wants_timestamp_timers" to false,
            )

            val users = (0 until people).map { i ->
                val access = when (i) {
                    0 -> listOf("administrator")
                    1, 2 -> listOf("manager", "project_creator", "billable_rates_manager")
                    else -> listOf("member")
                }
                mutableMapOf<String, Any?>(
                    "id" to id(), "first_name" to "Person", "last_name" to "Number $i", "email" to "person$i-$seed@fixture.example",
                    "timezone" to "America/New_York", "has_access_to_all_future_projects" to (i == 1), "is_contractor" to (i == people - 1),
                    "is_active" to (i != people - 2 || people < 4), "weekly_capacity" to 144_000,
                    "default_hourly_rate" to BigDecimal(80 + i * 10).setScale(2), "cost_rate" to BigDecimal(40 + i * 5).setScale(2),
                    "roles" to listOf(if (i % 2 == 0) "Design" else "Engineering"), "access_roles" to access,
                )
            }.toMutableList()
            val roles = mutableListOf<Json>(
                mutableMapOf("id" to id(), "name" to "Design", "user_ids" to users.filterIndexed { i, _ -> i % 2 == 0 }.map { it["id"] }),
                mutableMapOf("id" to id(), "name" to "Engineering", "user_ids" to users.filterIndexed { i, _ -> i % 2 == 1 }.map { it["id"] }),
            )

            val clientList = (0 until clients).map { i ->
                mutableMapOf<String, Any?>(
                    "id" to id(), "name" to "Client $i of $seed", "is_active" to (i != clients - 1 || clients < 3),
                    "address" to "${i + 1} Harbour Road\nSuite ${i * 3}\nSpringfield", "currency" to currencies[i % currencies.size],
                )
            }.toMutableList()
            val contacts = clientList.flatMap { c ->
                (0..rnd.nextInt(2)).map { j ->
                    mutableMapOf<String, Any?>(
                        "id" to id(), "client" to mapOf("id" to c["id"], "name" to c["name"]), "title" to "Finance", "first_name" to "Contact",
                        "last_name" to "$j", "email" to "contact$j-${c["id"]}@client.example", "phone_office" to "+1 555 0100", "phone_mobile" to "",
                    )
                }
            }.toMutableList()

            val tasks = mutableListOf<Json>(
                mutableMapOf("id" to id(), "name" to "Design", "billable_by_default" to true, "default_hourly_rate" to BigDecimal("90.00"), "is_default" to true, "is_active" to true),
                mutableMapOf("id" to id(), "name" to "Development", "billable_by_default" to true, "default_hourly_rate" to BigDecimal("110.00"), "is_default" to true, "is_active" to true),
                mutableMapOf("id" to id(), "name" to "Project management", "billable_by_default" to true, "default_hourly_rate" to BigDecimal("75.00"), "is_default" to false, "is_active" to true),
                mutableMapOf("id" to id(), "name" to "Meetings", "billable_by_default" to false, "default_hourly_rate" to null, "is_default" to false, "is_active" to true),
                mutableMapOf("id" to id(), "name" to "Research", "billable_by_default" to true, "default_hourly_rate" to BigDecimal("95.00"), "is_default" to false, "is_active" to false),
            )

            val projects = mutableListOf<Json>()
            val taskAssignments = mutableListOf<Json>()
            val userAssignments = mutableListOf<Json>()
            var p = 0
            clientList.forEach { c ->
                val currency = c["currency"] as String
                repeat(projectsPerClient) { k ->
                    val by = billBy[p % billBy.size]
                    val budget = budgetBy[p % budgetBy.size]
                    val project = mutableMapOf<String, Any?>(
                        "id" to id(), "client" to mapOf("id" to c["id"], "name" to c["name"], "currency" to currency),
                        "name" to "Project $k", "code" to "P-$p", "is_active" to (p % 7 != 6), "is_billable" to (by != "none" || p % 2 == 0),
                        "is_fixed_fee" to (p % 5 == 4), "bill_by" to by, "hourly_rate" to if (by == "Project") money(100.0 + p, currency) else null,
                        "budget_by" to budget, "budget_is_monthly" to (p % 4 == 1),
                        "budget" to if (budget in setOf("project", "task", "person")) BigDecimal(120) else null,
                        "cost_budget" to if (budget == "project_cost") money(15_000.0, currency) else null,
                        "cost_budget_include_expenses" to (budget == "project_cost"), "notify_when_over_budget" to true,
                        "over_budget_notification_percentage" to BigDecimal(80), "show_budget_to_all" to (p % 3 == 0),
                        "fee" to if (p % 5 == 4) money(9_000.0, currency) else null, "notes" to "Notes for project $p",
                        "starts_on" to today.minusWeeks(weeks.toLong() + 2).toString(), "ends_on" to null,
                    )
                    projects += project
                    tasks.take(4).forEachIndexed { t, task ->
                        taskAssignments += mutableMapOf(
                            "id" to id(), "project" to mapOf("id" to project["id"]), "task" to mapOf("id" to task["id"]), "is_active" to true,
                            "billable" to (task["billable_by_default"] as Boolean), "hourly_rate" to if (by == "Tasks") money(85.0 + t * 15, currency) else null,
                            "budget" to when (budget) { "task" -> BigDecimal(40); "task_fees" -> money(4_000.0, currency); else -> null },
                        )
                    }
                    users.filterIndexed { i, _ -> (i + p) % 2 == 0 || i == 0 }.forEachIndexed { u, user ->
                        val custom = by == "People" && u % 2 == 1
                        userAssignments += mutableMapOf(
                            "id" to id(), "project" to mapOf("id" to project["id"]), "user" to mapOf("id" to user["id"]), "is_active" to true,
                            "is_project_manager" to (u == 0), "use_default_rates" to !custom,
                            "hourly_rate" to if (custom) money(150.0, currency) else null, "budget" to if (budget == "person") BigDecimal(30) else null,
                        )
                    }
                    p++
                }
            }

            val expenseCategories = mutableListOf<Json>(
                mutableMapOf("id" to id(), "name" to "Travel", "unit_name" to null, "unit_price" to null, "is_active" to true),
                mutableMapOf("id" to id(), "name" to "Meals", "unit_name" to null, "unit_price" to null, "is_active" to true),
                mutableMapOf("id" to id(), "name" to "Mileage", "unit_name" to "mile", "unit_price" to BigDecimal("0.67"), "is_active" to true),
            )

            // Time: each assigned person logs a few entries a week, with Harvest's rate precedence.
            val timeEntries = mutableListOf<Json>()
            val usersById = users.associateBy { it["id"] as Long }
            val tasksById = tasks.associateBy { it["id"] as Long }
            projects.forEach { project ->
                val currency = (project["client"] as Map<*, *>)["currency"] as String
                val pid = project["id"] as Long
                val pTasks = taskAssignments.filter { (it["project"] as Map<*, *>)["id"] == pid }
                val pUsers = userAssignments.filter { (it["project"] as Map<*, *>)["id"] == pid }
                (0 until weeks).forEach { w ->
                    pUsers.forEach { ua ->
                        repeat(rnd.nextInt(0, 3)) {
                            val day = today.minusWeeks(w.toLong()).minusDays(rnd.nextLong(0, 5))
                            if (day.isAfter(today)) return@repeat
                            val ta = pTasks[rnd.nextInt(pTasks.size)]
                            val user = usersById.getValue((ua["user"] as Map<*, *>)["id"] as Long)
                            val task = tasksById.getValue((ta["task"] as Map<*, *>)["id"] as Long)
                            val billable = (project["is_billable"] as Boolean) && (ta["billable"] as Boolean)
                            val rate: BigDecimal? = when (project["bill_by"]) {
                                "Project" -> project["hourly_rate"] as BigDecimal?
                                "Tasks" -> (ta["hourly_rate"] as BigDecimal?) ?: task["default_hourly_rate"] as BigDecimal?
                                "People" -> if (ua["use_default_rates"] as Boolean) user["default_hourly_rate"] as BigDecimal? else ua["hourly_rate"] as BigDecimal?
                                else -> null
                            }?.let { money(it.toDouble(), currency) }
                            val hours = BigDecimal(rnd.nextInt(1, 33) * 25).divide(BigDecimal(100))
                            val approved = w >= 2 && rnd.nextInt(3) == 0
                            timeEntries += mutableMapOf(
                                "id" to id(), "spent_date" to day.toString(), "user" to mapOf("id" to user["id"], "name" to "${user["first_name"]} ${user["last_name"]}"),
                                "client" to mapOf("id" to (project["client"] as Map<*, *>)["id"]), "project" to mapOf("id" to pid), "task" to mapOf("id" to task["id"]),
                                "external_reference" to if (rnd.nextInt(10) == 0) mapOf("id" to "ISSUE-${rnd.nextInt(1000)}", "group_id" to "42", "permalink" to "https://tracker.example/issues/1", "service" to "tracker.example") else null,
                                "invoice" to null, "hours" to hours, "rounded_hours" to hours, "notes" to "Work on ${task["name"]}",
                                "is_locked" to approved, "locked_reason" to if (approved) "Item Approved and Locked for this Time Period" else null,
                                "approval_status" to if (approved) "approved" else if (w == 1 && rnd.nextInt(4) == 0) "submitted" else "unsubmitted",
                                "is_billed" to false, "timer_started_at" to null, "started_time" to "9:00am", "ended_time" to "5:30pm", "is_running" to false,
                                "billable" to billable, "billable_rate" to if (billable) rate else null, "cost_rate" to user["cost_rate"],
                                "updated_at" to Instant.now().toString(),
                            )
                        }
                    }
                }
            }
            // One timer still running today.
            timeEntries.firstOrNull { it["spent_date"] == today.toString() }?.let { e ->
                e["is_running"] = true
                e["timer_started_at"] = Instant.now().minusSeconds(1800).toString()
                e["ended_time"] = null
            }

            val expenses = mutableListOf<Json>()
            val receipts = mutableMapOf<Long, ByteArray>()
            projects.forEachIndexed { i, project ->
                val currency = (project["client"] as Map<*, *>)["currency"] as String
                val ua = userAssignments.first { (it["project"] as Map<*, *>)["id"] == project["id"] }
                (0 until 3).forEach { k ->
                    val eid = id()
                    val withReceipt = receiptBaseUrl.isNotEmpty() && k == 0
                    if (withReceipt) receipts[eid] = "receipt $eid".toByteArray()
                    expenses += mutableMapOf(
                        "id" to eid, "project" to mapOf("id" to project["id"]), "expense_category" to mapOf("id" to expenseCategories[k % 3]["id"]),
                        "user" to mapOf("id" to (ua["user"] as Map<*, *>)["id"]),
                        "receipt" to if (withReceipt) mapOf("url" to "$receiptBaseUrl/$eid", "file_name" to "receipt-$eid.pdf", "file_size" to 10, "content_type" to "application/pdf") else null,
                        "invoice" to null, "notes" to "Expense $k", "units" to if (k == 2) BigDecimal("12.50") else null,
                        "total_cost" to money(20.0 + i * 3 + k, currency), "billable" to (k != 1), "is_locked" to false, "locked_reason" to null,
                        "approval_status" to "unsubmitted", "is_billed" to false, "spent_date" to today.minusWeeks((k * weeks / 3).toLong()).toString(),
                    )
                }
            }

            // Invoices: built from older billable entries; numbering with a prefix and padding.
            val invoices = mutableListOf<Json>()
            val payments = mutableMapOf<Long, MutableList<Json>>()
            val estimates = mutableListOf<Json>()
            var number = 1
            clientList.forEach { c ->
                val currency = c["currency"] as String
                val clientProjects = projects.filter { (it["client"] as Map<*, *>)["id"] == c["id"] }.map { it["id"] }
                val candidates = timeEntries.filter { (it["project"] as Map<*, *>)["id"] in clientProjects && it["billable"] == true && it["invoice"] == null && it["is_running"] == false }
                    .sortedBy { it["spent_date"] as String }
                val chunks = if (invoicesPerClient == 0) emptyList() else candidates.take(candidates.size / 2).chunked((candidates.size / 2 / invoicesPerClient).coerceAtLeast(1)).take(invoicesPerClient)
                chunks.forEachIndexed { k, chunk ->
                    val invId = id()
                    val state = listOf("paid", "open", "closed", "draft")[k % 4]
                    val lines = chunk.groupBy { (it["project"] as Map<*, *>)["id"] }.map { (projectId, es) ->
                        val amount = es.fold(BigDecimal.ZERO) { acc, e -> acc + (e["hours"] as BigDecimal) * (e["billable_rate"] as BigDecimal? ?: BigDecimal.ZERO) }
                            .setScale(if (currency == "JPY") 0 else 2, RoundingMode.HALF_UP)
                        mutableMapOf<String, Any?>(
                            "id" to id(), "project" to mapOf("id" to projectId), "kind" to "Service", "description" to "Services",
                            "quantity" to BigDecimal.ONE, "unit_price" to amount, "amount" to amount, "taxed" to true, "taxed2" to false,
                        )
                    }
                    val subtotal = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l["amount"] as BigDecimal }
                    val tax = (subtotal * BigDecimal("0.10")).setScale(if (currency == "JPY") 0 else 2, RoundingMode.HALF_UP)
                    val total = subtotal + tax
                    val due = when (state) { "paid", "closed" -> BigDecimal.ZERO; "open" -> (total / BigDecimal(2)).setScale(if (currency == "JPY") 0 else 2, RoundingMode.HALF_UP); else -> total }
                    val issued = LocalDate.parse(chunk.last()["spent_date"] as String).plusDays(1)
                    invoices += mutableMapOf(
                        "id" to invId, "client" to mapOf("id" to c["id"], "name" to c["name"]), "line_items" to lines,
                        "number" to if (state == "draft") null else "INV-%04d".format(number++), "purchase_order" to "PO-$k",
                        "amount" to total, "due_amount" to due, "tax" to BigDecimal("10.0"), "tax_amount" to tax, "tax2" to null, "tax2_amount" to null,
                        "discount" to null, "discount_amount" to null, "subject" to "Invoice $k", "notes" to null, "currency" to currency, "state" to state,
                        "period_start" to chunk.first()["spent_date"], "period_end" to chunk.last()["spent_date"], "issue_date" to issued.toString(),
                        "due_date" to issued.plusDays(30).toString(), "payment_term" to "net 30",
                        "sent_at" to if (state == "draft") null else "${issued}T10:00:00Z", "paid_at" to if (state == "paid") "${issued.plusDays(10)}T10:00:00Z" else null,
                        "paid_date" to if (state == "paid") issued.plusDays(10).toString() else null, "closed_at" to if (state == "closed") "${issued.plusDays(40)}T10:00:00Z" else null,
                        "created_at" to "${issued}T09:00:00Z",
                    )
                    if (state != "draft") {
                        chunk.forEach { e ->
                            e["invoice"] = mapOf("id" to invId, "number" to invoices.last()["number"])
                            e["is_billed"] = true
                            e["is_locked"] = true
                            e["locked_reason"] = "Item Invoiced and Locked for this Time Period"
                        }
                    }
                    payments[invId] = when (state) {
                        "paid" -> mutableListOf(mutableMapOf("id" to id(), "amount" to total, "paid_at" to "${issued.plusDays(10)}T10:00:00Z", "paid_date" to issued.plusDays(10).toString(), "recorded_by" to "Person Number 0", "notes" to null, "transaction_id" to null, "payment_gateway" to null))
                        "open" -> mutableListOf(mutableMapOf("id" to id(), "amount" to total - due, "paid_at" to "${issued.plusDays(5)}T10:00:00Z", "paid_date" to issued.plusDays(5).toString(), "recorded_by" to "Person Number 0", "notes" to "First half", "transaction_id" to "txn_$invId", "payment_gateway" to mapOf("id" to 1, "name" to "Stripe")))
                        else -> mutableListOf()
                    }
                }
                estimates += mutableMapOf("id" to id(), "client" to mapOf("id" to c["id"]), "number" to "EST-${c["id"]}", "issue_date" to today.minusWeeks(weeks.toLong()).toString(), "amount" to money(5_000.0, currency), "currency" to currency, "state" to "accepted")
            }

            return HarvestFixture(company, users, roles, clientList, contacts, tasks, projects, taskAssignments, userAssignments, expenseCategories, timeEntries, expenses, invoices, payments, estimates, receipts)
        }
    }
}
