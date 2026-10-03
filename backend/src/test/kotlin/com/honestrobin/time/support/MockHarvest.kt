// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the Harvest API v2, serving a [HarvestFixture]: bearer token and account header
 * checks, page-based pagination with `links.next`, date filters, the time report, receipt
 * downloads, and switches for latency, throttling (429 with Retry-After) and failures.
 */
object MockHarvest {
    const val TOKEN = "test-harvest-token"
    const val ACCOUNT_ID = "4242"

    private val mapper = ObjectMapper()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { respond(exchange, 500, mapOf("error" to it.toString())) } }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v2"
    val receiptUrl: String get() = "http://127.0.0.1:${server.address.port}/receipts"

    @Volatile var fixture: HarvestFixture = HarvestFixture.agency(people = 2, clients = 1, projectsPerClient = 1, weeks = 1)
    @Volatile var latencyMs: Long = 0
    /** Every nth request is answered with 429 and Retry-After: 1. */
    @Volatile var throttleEvery: Int = 0
    /** Requests whose path and query match fail with HTTP 500 until cleared. */
    @Volatile var failWhen: Regex? = null
    val requests = AtomicInteger()
    val throttled = AtomicInteger()

    fun reset(f: HarvestFixture) {
        fixture = f
        latencyMs = 0
        throttleEvery = 0
        failWhen = null
        requests.set(0)
        throttled.set(0)
    }

    private fun handle(ex: HttpExchange) {
        val n = requests.incrementAndGet()
        if (latencyMs > 0) Thread.sleep(latencyMs)
        val path = ex.requestURI.path
        val query = (ex.requestURI.rawQuery ?: "").split("&").filter { it.contains("=") }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }

        if (path.startsWith("/receipts/")) {
            val bytes = fixture.receipts[path.substringAfterLast("/").toLong()] ?: return respond(ex, 404, mapOf("error" to "no receipt"))
            ex.responseHeaders.add("Content-Type", "application/pdf")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return
        }
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer $TOKEN" || ex.requestHeaders.getFirst("Harvest-Account-Id") != ACCOUNT_ID) {
            return respond(ex, 401, mapOf("error" to "invalid_token"))
        }
        if (ex.requestHeaders.getFirst("User-Agent").isNullOrBlank()) return respond(ex, 400, mapOf("error" to "User-Agent required"))
        failWhen?.let { if (it.containsMatchIn(path + "?" + (ex.requestURI.rawQuery ?: ""))) return respond(ex, 500, mapOf("error" to "boom")) }
        if (throttleEvery > 0 && n % throttleEvery == 0) {
            throttled.incrementAndGet()
            ex.responseHeaders.add("Retry-After", "1")
            return respond(ex, 429, mapOf("error" to "Too Many Requests"))
        }

        val f = fixture
        val from = query["from"]?.let(LocalDate::parse)
        val to = query["to"]?.let(LocalDate::parse)
        fun inRange(date: Any?) = LocalDate.parse(date as String).let { d -> (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to)) }
        // updated_since, as Harvest's lists take it: only records changed since then (no updated_at: unchanged).
        val since = query["updated_since"]?.let(java.time.Instant::parse)
        fun changed(list: List<Map<String, Any?>>) = if (since == null) list else list.filter { r -> (r["updated_at"] as String?)?.let { !java.time.Instant.parse(it).isBefore(since) } ?: false }
        val route = path.removePrefix("/v2")
        when {
            route == "/company" -> respond(ex, 200, f.company)
            route == "/users" -> page(ex, query, "users", changed(f.users))
            route == "/roles" -> page(ex, query, "roles", f.roles)
            route == "/clients" -> page(ex, query, "clients", changed(f.clients))
            route == "/contacts" -> page(ex, query, "contacts", changed(f.contacts))
            route == "/tasks" -> page(ex, query, "tasks", changed(f.tasks))
            route == "/projects" -> page(ex, query, "projects", changed(f.projects))
            route == "/task_assignments" -> page(ex, query, "task_assignments", changed(f.taskAssignments))
            route == "/user_assignments" -> page(ex, query, "user_assignments", changed(f.userAssignments))
            route == "/expense_categories" -> page(ex, query, "expense_categories", changed(f.expenseCategories))
            route == "/time_entries" -> page(ex, query, "time_entries", changed(f.timeEntries.filter { inRange(it["spent_date"]) }))
            route == "/expenses" -> page(ex, query, "expenses", changed(f.expenses.filter { inRange(it["spent_date"]) }))
            route == "/invoices" -> page(ex, query, "invoices", changed(f.invoices.filter { query["state"] == null || it["state"] == query["state"] }))
            route.matches(Regex("^/invoices/\\d+/payments$")) -> page(ex, query, "invoice_payments", f.payments[route.split("/")[2].toLong()].orEmpty())
            route == "/estimates" -> page(ex, query, "estimates", f.estimates)
            route == "/reports/time/projects" -> page(ex, query, "results", timeReport(f, from!!, to!!))
            else -> respond(ex, 404, mapOf("error" to "not found: $route"))
        }
    }

    /** Harvest's time report: per project, hours and billable amount over a date range. */
    private fun timeReport(f: HarvestFixture, from: LocalDate, to: LocalDate): List<Map<String, Any?>> =
        f.timeEntries.filter { LocalDate.parse(it["spent_date"] as String).let { d -> !d.isBefore(from) && !d.isAfter(to) } }
            .groupBy { (it["project"] as Map<*, *>)["id"] as Long }
            .map { (projectId, entries) ->
                val project = f.projectOf(projectId)
                val client = project["client"] as Map<*, *>
                val total = entries.fold(BigDecimal.ZERO) { a, e -> a + e["hours"] as BigDecimal }
                val billable = entries.filter { it["billable"] == true }
                mapOf(
                    "client_id" to client["id"], "client_name" to client["name"], "project_id" to projectId, "project_name" to project["name"],
                    "currency" to client["currency"], "total_hours" to total,
                    "billable_hours" to billable.fold(BigDecimal.ZERO) { a, e -> a + e["hours"] as BigDecimal },
                    "billable_amount" to billable.fold(BigDecimal.ZERO) { a, e -> a + (e["hours"] as BigDecimal) * (e["billable_rate"] as BigDecimal? ?: BigDecimal.ZERO) }
                        .setScale(2, RoundingMode.HALF_UP),
                )
            }

    private fun page(ex: HttpExchange, query: Map<String, String>, key: String, all: List<Map<String, Any?>>) {
        val perPage = query["per_page"]?.toInt()?.coerceIn(1, 2000) ?: 2000
        val pageNo = query["page"]?.toInt() ?: 1
        val pages = ((all.size + perPage - 1) / perPage).coerceAtLeast(1)
        val items = all.drop((pageNo - 1) * perPage).take(perPage)
        val base = "$baseUrl${ex.requestURI.path.removePrefix("/v2")}"
        fun link(p: Int) = base + "?" + (query - "page" + ("page" to p.toString())).entries.joinToString("&") { "${it.key}=${it.value}" }
        respond(
            ex, 200,
            mapOf(
                key to items, "per_page" to perPage, "total_pages" to pages, "total_entries" to all.size, "page" to pageNo,
                "next_page" to if (pageNo < pages) pageNo + 1 else null, "previous_page" to if (pageNo > 1) pageNo - 1 else null,
                "links" to mapOf("first" to link(1), "next" to if (pageNo < pages) link(pageNo + 1) else null, "previous" to if (pageNo > 1) link(pageNo - 1) else null, "last" to link(pages)),
            ),
        )
    }

    private fun respond(ex: HttpExchange, status: Int, body: Any) {
        val bytes = mapper.writeValueAsBytes(body)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
