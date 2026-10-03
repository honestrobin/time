// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** A stand-in for the parts of the Stripe API the app calls: account lookup, Checkout and Connect OAuth. */
object MockStripe {
    const val PLATFORM_KEY = "sk_test_platform"
    const val CLIENT_ID = "ca_test_platform"
    const val PLATFORM_WEBHOOK_SECRET = "whsec_platform_test"
    const val ACCOUNT_KEY = "sk_test_own_account"

    data class Call(val method: String, val path: String, val authorization: String?, val stripeAccount: String?, val form: Map<String, String>)

    val calls = CopyOnWriteArrayList<Call>()
    @Volatile var connectedAccount = "acct_connected_1"

    private val mapper = ObjectMapper()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { handle(it) }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readAllBytes().decodeToString()
        val form = body.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
        }
        val auth = ex.requestHeaders.getFirst("Authorization")
        calls += Call(ex.requestMethod, ex.requestURI.path, auth, ex.requestHeaders.getFirst("Stripe-Account"), form)
        val key = auth?.removePrefix("Bearer ")
        when (ex.requestURI.path) {
            "/v1/account" ->
                if (key == ACCOUNT_KEY) respond(ex, 200, mapOf("id" to "acct_own_1", "business_profile" to mapOf("name" to "Fjord & Pine Studio")))
                else respond(ex, 401, mapOf("error" to mapOf("message" to "Invalid API Key provided")))
            "/v1/checkout/sessions" ->
                if (key == ACCOUNT_KEY || key == PLATFORM_KEY) respond(ex, 200, mapOf("id" to "cs_test_${calls.size}", "url" to "https://checkout.stripe.test/pay/cs_test_${calls.size}"))
                else respond(ex, 401, mapOf("error" to mapOf("message" to "Invalid API Key provided")))
            "/oauth/token" ->
                if (key == PLATFORM_KEY && form["code"] == "good-code") respond(ex, 200, mapOf("stripe_user_id" to connectedAccount, "scope" to "read_write"))
                else respond(ex, 400, mapOf("error" to "invalid_grant", "error_description" to "Authorization code expired"))
            else -> respond(ex, 404, mapOf("error" to mapOf("message" to "not found")))
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: Any) {
        val bytes = mapper.writeValueAsBytes(body)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
