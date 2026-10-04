// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stand-ins for QuickBooks Online and Xero: OAuth tokens, the few reads the app makes, and
 * creating customers, invoices and payments, with each API's idempotency (QuickBooks'
 * `requestid`, Xero's `Idempotency-Key`).
 */
object MockAccounting {
    const val CLIENT_ID = "acct_client"
    const val CLIENT_SECRET = "acct_secret"
    const val REALM = "9130354"
    const val XERO_TENANT = "xero-tenant-1"

    data class Call(val method: String, val path: String, val query: String?, val headers: Map<String, String?>, val body: JsonNode?, val raw: String)

    val calls = CopyOnWriteArrayList<Call>()

    /** Objects created per (system, kind), e.g. "qbo:Invoice". */
    val created = ConcurrentHashMap<String, MutableList<JsonNode>>()

    /** Seconds until access tokens expire, so tests can force a refresh. */
    @Volatile var expiresIn = 3600L

    private val idempotent = ConcurrentHashMap<String, String>()
    private val mapper = ObjectMapper()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { handle(it) }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun createdCount(key: String) = created[key]?.size ?: 0

    private fun handle(ex: HttpExchange) {
        val raw = ex.requestBody.readAllBytes().decodeToString()
        val path = ex.requestURI.path
        val query = ex.requestURI.rawQuery
        val headers = listOf("Authorization", "Xero-tenant-id", "Idempotency-Key").associateWith { ex.requestHeaders.getFirst(it) }
        val body = if (raw.isBlank() || raw.trimStart().first() !in "{[") null else mapper.readTree(raw)
        calls.add(Call(ex.requestMethod, path, query, headers, body, raw))
        val form = raw.split("&").filter { it.contains("=") }.associate { URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
        val bearer = headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }

        fun tokens() = mapOf("access_token" to "at_${UUID.randomUUID()}", "refresh_token" to "rt_${UUID.randomUUID()}", "expires_in" to expiresIn, "token_type" to "bearer")
        fun create(system: String, kind: String, key: String?, make: (String) -> Map<String, Any?>): Map<String, Any?> {
            val id = key?.let { idempotent.computeIfAbsent("$system:$it") { UUID.randomUUID().toString() } } ?: UUID.randomUUID().toString()
            val list = created.computeIfAbsent("$system:$kind") { CopyOnWriteArrayList() }
            if (list.none { it["id"].asText() == id }) list.add(mapper.valueToTree<JsonNode>(mapOf("id" to id, "body" to body)))
            return make(id)
        }

        val (status, response: Any?) = when {
            // OAuth (both)
            path.endsWith("/tokens/bearer") || path.endsWith("/connect/token") ->
                if (form["grant_type"] == "refresh_token" || form["code"] == "good-code") 200 to tokens() else 400 to mapOf("error" to "invalid_grant")
            bearer == null -> 401 to mapOf("error" to "unauthorized")
            // Xero
            path == "/connections" -> 200 to listOf(mapOf("tenantId" to XERO_TENANT, "tenantName" to "Fjord & Pine (Xero)", "tenantType" to "ORGANISATION"))
            path == "/api.xro/2.0/TaxRates" -> 200 to mapOf("TaxRates" to listOf(mapOf("TaxType" to "OUTPUT2", "Name" to "20% (VAT on Income)", "Status" to "ACTIVE"), mapOf("TaxType" to "ZERORATEDOUTPUT", "Name" to "Zero Rated Income", "Status" to "ACTIVE")))
            path == "/api.xro/2.0/Accounts" -> 200 to mapOf("Accounts" to listOf(
                mapOf("AccountID" to "acc-sales", "Code" to "200", "Name" to "Sales", "Type" to "REVENUE", "Status" to "ACTIVE"),
                mapOf("AccountID" to "acc-bank", "Code" to "090", "Name" to "Business Bank", "Type" to "BANK", "Status" to "ACTIVE"),
            ))
            path == "/api.xro/2.0/Contacts" && ex.requestMethod == "GET" -> 200 to mapOf("Contacts" to emptyList<Any>())
            path == "/api.xro/2.0/Contacts" -> 200 to create("xero", "Contact", null) { mapOf("Contacts" to listOf(mapOf("ContactID" to it))) }
            path == "/api.xro/2.0/Invoices" -> 200 to create("xero", "Invoice", headers["Idempotency-Key"]) { mapOf("Invoices" to listOf(mapOf("InvoiceID" to it))) }
            path == "/api.xro/2.0/Payments" -> 200 to create("xero", "Payment", headers["Idempotency-Key"]) { mapOf("Payments" to listOf(mapOf("PaymentID" to it))) }
            // QuickBooks
            path.endsWith("/companyinfo/$REALM") -> 200 to mapOf("CompanyInfo" to mapOf("CompanyName" to "Fjord & Pine (QuickBooks)"))
            path.endsWith("/query") -> {
                val q = URLDecoder.decode(query.orEmpty().substringAfter("query=").substringBefore("&"), Charsets.UTF_8)
                200 to mapOf("QueryResponse" to when {
                    q.contains("from TaxCode") -> mapOf("TaxCode" to listOf(mapOf("Id" to "TAX19", "Name" to "19% S"), mapOf("Id" to "TAX7", "Name" to "7% S")))
                    q.contains("from Item") -> mapOf("Item" to listOf(mapOf("Id" to "1", "Name" to "Services")))
                    q.contains("from Account") -> mapOf("Account" to listOf(mapOf("Id" to "35", "Name" to "Checking")))
                    q.contains("from Customer") -> {
                        val name = Regex("DisplayName = '(.*)'").find(q)?.groupValues?.get(1)?.replace("\\'", "'")
                        mapOf("Customer" to created["qbo:Customer"].orEmpty().filter { it["body"]["DisplayName"].asText() == name }.map { mapOf("Id" to it["id"].asText()) })
                    }
                    else -> emptyMap()
                })
            }
            path.endsWith("/customer") -> 200 to create("qbo", "Customer", null) { mapOf("Customer" to mapOf("Id" to it)) }
            path.endsWith("/invoice") -> 200 to create("qbo", "Invoice", requestId(query)) { mapOf("Invoice" to mapOf("Id" to it)) }
            path.endsWith("/payment") -> 200 to create("qbo", "Payment", requestId(query)) { mapOf("Payment" to mapOf("Id" to it)) }
            else -> 404 to mapOf("error" to "not found")
        }
        val bytes = mapper.writeValueAsBytes(response)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun requestId(query: String?) = query?.split("&")?.firstOrNull { it.startsWith("requestid=") }?.substringAfter("=")
}
