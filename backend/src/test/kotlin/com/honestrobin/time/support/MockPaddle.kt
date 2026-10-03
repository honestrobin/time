// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A stand-in for the parts of the Paddle Billing API the app calls: seat changes and the customer portal. */
object MockPaddle {
    const val API_KEY = "pdl_test_key"
    const val WEBHOOK_SECRET = "pdl_ntfset_test"
    const val MONTHLY_PRICE = "pri_team_monthly"
    const val ANNUAL_PRICE = "pri_team_annual"

    data class Call(val method: String, val path: String, val authorization: String?, val body: JsonNode?)

    val calls = CopyOnWriteArrayList<Call>()

    private val mapper = ObjectMapper()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val raw = ex.requestBody.readAllBytes()
                val body = if (raw.isEmpty()) null else mapper.readTree(raw)
                calls.add(Call(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), body))
                val (status, response) = when {
                    ex.requestHeaders.getFirst("Authorization") != "Bearer $API_KEY" -> 403 to mapOf("error" to mapOf("detail" to "Invalid API key"))
                    ex.requestMethod == "PATCH" && ex.requestURI.path.startsWith("/subscriptions/") -> 200 to mapOf("data" to mapOf("id" to ex.requestURI.path.substringAfterLast('/')))
                    ex.requestURI.path.endsWith("/portal-sessions") ->
                        200 to mapOf("data" to mapOf("urls" to mapOf("general" to mapOf("overview" to "https://customer-portal.paddle.test/overview"))))
                    else -> 404 to mapOf("error" to mapOf("detail" to "Not found"))
                }
                val bytes = mapper.writeValueAsBytes(response)
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
}
