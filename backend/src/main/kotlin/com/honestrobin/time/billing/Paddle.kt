// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.billing

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Paddle Billing, the merchant of record for Honest Robin Cloud (spec §14). Paddle handles tax,
 * invoices and payment methods; we keep the subscription state it reports. (VERIFY against the
 * Paddle Billing API docs before going live.)
 */
@ConfigurationProperties(prefix = "honestrobin.paddle")
data class PaddleSettings(
    val apiBaseUrl: String = "https://api.paddle.com",
    /** `sandbox` or `production`, for Paddle.js in the browser. */
    val environment: String = "production",
    val apiKey: String = "",
    /** Client-side token for Paddle.js checkout. */
    val clientToken: String = "",
    /** Secret of the webhook (notification) destination. */
    val webhookSecret: String = "",
    val teamMonthlyPriceId: String = "",
    val teamAnnualPriceId: String = "",
) {
    val configured: Boolean get() = apiKey.isNotBlank() && webhookSecret.isNotBlank() && teamMonthlyPriceId.isNotBlank()
}

/** List prices shown on the billing page; Paddle's prices are what is charged. Prices live in config, not code. */
@ConfigurationProperties(prefix = "honestrobin.billing")
data class BillingPrices(
    val currency: String = "EUR",
    /** Per seat, paying monthly. */
    val teamMonthlyMinor: Long = 850,
    /** Per seat per month, paying yearly. */
    val teamAnnualMonthlyMinor: Long = 700,
)

/** The free plan (spec §14: Free Solo, one seat). A setting so it can change without a release. */
@ConfigurationProperties(prefix = "honestrobin.billing.free-plan")
class FreePlan {
    var seats: Int = 1
}

class PaddleException(val status: Int, message: String) : RuntimeException(message)

class PaddleClient(private val settings: PaddleSettings, private val json: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Changes the seat count, staying on the subscription's own price (that keeps the price lock). */
    fun updateQuantity(subscriptionId: String, priceId: String, quantity: Int): JsonNode =
        call(
            "PATCH", "/subscriptions/$subscriptionId",
            mapOf("items" to listOf(mapOf("price_id" to priceId, "quantity" to quantity)), "proration_billing_mode" to "prorated_immediately"),
        )

    /** A subscription as Paddle has it now, for what our record may not have heard yet (`data.status`). */
    fun subscription(subscriptionId: String): JsonNode = call("GET", "/subscriptions/$subscriptionId", null)

    /** Cancels a subscription now, so it never renews: for an account being deleted, a second subscription, or one past due. */
    fun cancel(subscriptionId: String): JsonNode = call("POST", "/subscriptions/$subscriptionId/cancel", mapOf("effective_from" to "immediately"))

    /**
     * Cancels a subscription at the end of the billing period that's paid for: it runs as before
     * until then, and doesn't renew. (VERIFY with Paddle's sandbox: `next_billing_period` schedules
     * the cancellation, the answer's `data.scheduled_change` has `action: cancel` and its
     * `effective_at`, and Paddle sends `subscription.updated` now and `subscription.canceled` then.)
     */
    fun cancelAtPeriodEnd(subscriptionId: String): JsonNode =
        call("POST", "/subscriptions/$subscriptionId/cancel", mapOf("effective_from" to "next_billing_period"))

    /**
     * Takes back a cancellation that hasn't happened yet, so the subscription renews as before.
     * (VERIFY with Paddle's sandbox: setting `scheduled_change` to null removes it, and charges
     * nothing.)
     */
    fun removeScheduledChange(subscriptionId: String): JsonNode =
        call("PATCH", "/subscriptions/$subscriptionId", json.createObjectNode().putNull("scheduled_change"))

    /** A link to Paddle's customer portal: payment method, invoices, cancelling. */
    fun portalUrl(customerId: String, subscriptionId: String?): String {
        val body = call("POST", "/customers/$customerId/portal-sessions", mapOf("subscription_ids" to listOfNotNull(subscriptionId)))
        return body["data"]["urls"]["general"]["overview"].asText()
    }

    private fun call(method: String, path: String, body: Any?): JsonNode {
        val builder = HttpRequest.newBuilder(URI.create(settings.apiBaseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
        val request = builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw PaddleException(0, "Paddle could not be reached: ${e.message}")
        }
        val parsed = runCatching { json.readTree(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) {
            throw PaddleException(response.statusCode(), parsed?.get("error")?.get("detail")?.asText() ?: "Paddle returned HTTP ${response.statusCode()}")
        }
        return parsed ?: throw PaddleException(response.statusCode(), "Paddle returned an empty response")
    }

    companion object {
        /**
         * Checks a `Paddle-Signature` header (`ts=…;h1=…`): HMAC-SHA256 of "ts:body" with the
         * destination's secret, within five minutes of now.
         */
        fun verifySignature(payload: String, header: String?, secret: String, now: Instant = Instant.now(), tolerance: Duration = Duration.ofMinutes(5)): Boolean {
            if (header.isNullOrBlank() || secret.isBlank()) return false
            val parts = header.split(";").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim() } }
            val ts = parts.firstOrNull { it.first == "ts" }?.second?.toLongOrNull() ?: return false
            if (Duration.between(Instant.ofEpochSecond(ts), now).abs() > tolerance) return false
            val expected = sign(secret, ts, payload)
            return parts.filter { it.first == "h1" }.any { MessageDigest.isEqual(it.second.toByteArray(), expected.toByteArray()) }
        }

        fun sign(secret: String, ts: Long, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal("$ts:$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
