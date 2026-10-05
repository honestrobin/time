// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A stand-in for the parts of the Paddle Billing API the app calls: seat changes, cancelling (now or at the period's end), taking a cancellation back, and the customer portal. */
object MockPaddle {
    const val API_KEY = "pdl_test_key"
    const val WEBHOOK_SECRET = "pdl_ntfset_test"
    const val MONTHLY_PRICE = "pri_team_monthly"
    const val ANNUAL_PRICE = "pri_team_annual"

    /** When the billing period of the subscriptions the tests make ends. */
    const val PERIOD_END = "2027-10-01T00:00:00Z"

    /** A request the app made; [status] is what the stand-in answered. */
    data class Call(val method: String, val path: String, val authorization: String?, val body: JsonNode?, val status: Int = 200)

    val calls = CopyOnWriteArrayList<Call>()

    /** While true, Paddle is down: every request is answered with 503 and changes nothing. */
    @Volatile var down = false

    /** Runs [block] with Paddle down, and brings it back afterwards. */
    fun <T> whileDown(block: () -> T): T {
        down = true
        try {
            return block()
        } finally {
            down = false
        }
    }

    /** While true, Paddle answers every request with 400, as it refuses changes in the 30 minutes before a renewal. */
    @Volatile var refusing = false

    /** Runs [block] with Paddle refusing, and lets it accept again afterwards. */
    fun <T> whileRefusing(block: () -> T): T {
        refusing = true
        try {
            return block()
        } finally {
            refusing = false
        }
    }

    private val mapper = ObjectMapper()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val raw = ex.requestBody.readAllBytes()
                val body = if (raw.isEmpty()) null else mapper.readTree(raw)
                val (status, response) = when {
                    down -> 503 to mapOf("error" to mapOf("detail" to "Service unavailable"))
                    refusing -> 400 to mapOf("error" to mapOf("detail" to "Changes can't be made to this subscription right now"))
                    ex.requestHeaders.getFirst("Authorization") != "Bearer $API_KEY" -> 403 to mapOf("error" to mapOf("detail" to "Invalid API key"))
                    ex.requestMethod == "PATCH" && ex.requestURI.path.startsWith("/subscriptions/") -> 200 to mapOf("data" to mapOf("id" to ex.requestURI.path.substringAfterLast('/')))
                    // At the end of the period, the subscription runs on with the cancellation scheduled.
                    ex.requestMethod == "POST" && ex.requestURI.path.matches(Regex("/subscriptions/[^/]+/cancel")) &&
                        body?.get("effective_from")?.asText() == "next_billing_period" ->
                        200 to mapOf(
                            "data" to mapOf(
                                "id" to ex.requestURI.path.split('/')[2], "status" to "active",
                                "scheduled_change" to mapOf("action" to "cancel", "effective_at" to PERIOD_END, "resume_at" to null),
                            ),
                        )
                    ex.requestMethod == "POST" && ex.requestURI.path.matches(Regex("/subscriptions/[^/]+/cancel")) ->
                        200 to mapOf("data" to mapOf("id" to ex.requestURI.path.split('/')[2], "status" to "canceled"))
                    ex.requestURI.path.endsWith("/portal-sessions") ->
                        200 to mapOf("data" to mapOf("urls" to mapOf("general" to mapOf("overview" to "https://customer-portal.paddle.test/overview"))))
                    else -> 404 to mapOf("error" to mapOf("detail" to "Not found"))
                }
                calls.add(Call(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), body, status))
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
