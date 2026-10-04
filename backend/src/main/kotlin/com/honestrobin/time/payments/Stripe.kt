// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.payments

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.honestrobin.time.platform.Money
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stripe for online invoice payments (spec §5.6). Two ways to connect: Stripe Connect (Standard
 * accounts, OAuth) when the platform keys are configured, as on Honest Robin Cloud; or the
 * account's own secret key, which works on any instance. Either way the money goes straight to the
 * account's Stripe balance and Honest Robin takes no application fee. (VERIFY against Stripe docs.)
 */
@ConfigurationProperties(prefix = "honestrobin.stripe")
data class StripeSettings(
    val apiBaseUrl: String = "https://api.stripe.com",
    val connectBaseUrl: String = "https://connect.stripe.com",
    /** Platform keys for Stripe Connect. Without them, accounts connect with their own key. */
    val connectClientId: String = "",
    val platformSecretKey: String = "",
    /** Signing secret of the platform's Connect webhook endpoint. */
    val platformWebhookSecret: String = "",
) {
    val connectEnabled get() = connectClientId.isNotBlank() && platformSecretKey.isNotBlank()
}

class StripeException(val status: Int, message: String) : RuntimeException(message)

data class CheckoutSession(val id: String, val url: String)

@Component
class StripeClient(private val settings: StripeSettings, private val json: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** The Stripe account behind a secret key: proves the key works and names the account. */
    fun account(secretKey: String): JsonNode = call("GET", "${settings.apiBaseUrl}/v1/account", secretKey, null, emptyMap())

    fun createCheckoutSession(secretKey: String, stripeAccount: String?, params: Map<String, String>): CheckoutSession {
        val body = call("POST", "${settings.apiBaseUrl}/v1/checkout/sessions", secretKey, stripeAccount, params)
        return CheckoutSession(body["id"].asText(), body["url"].asText())
    }

    /** Completes the Connect OAuth flow; returns the connected account's id (acct_…). */
    fun oauthToken(code: String): String =
        call("POST", "${settings.connectBaseUrl}/oauth/token", settings.platformSecretKey, null, mapOf("grant_type" to "authorization_code", "code" to code))["stripe_user_id"].asText()

    private fun call(method: String, url: String, secretKey: String, stripeAccount: String?, params: Map<String, String>): JsonNode {
        val form = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $secretKey")
            .header("Stripe-Version", API_VERSION)
        stripeAccount?.let { builder.header("Stripe-Account", it) }
        val request = if (method == "POST") {
            builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
        } else {
            builder.GET().build()
        }
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw StripeException(0, "Stripe could not be reached: ${e.message}")
        }
        val body = runCatching { json.readTree(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) {
            throw StripeException(response.statusCode(), body?.get("error")?.get("message")?.asText() ?: "Stripe returned HTTP ${response.statusCode()}")
        }
        return body ?: throw StripeException(response.statusCode(), "Stripe returned an empty response")
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        const val API_VERSION = "2024-06-20"

        // Where Stripe's amounts differ from ISO 4217 (docs.stripe.com/currencies, "Special
        // cases", read on 4 October 2026): ISK and UGX have no decimals, but Stripe still takes
        // them as two-decimal amounts ending in 00 ("to charge 5 ISK, provide 500"). HUF and TWD
        // are charged with two decimals, as ISO says; only their payouts are whole units.
        // An earlier version had this backwards and charged 1% of such invoices.
        private val STRIPE_TWO_DECIMAL = setOf("ISK", "UGX")

        private fun stripeDigits(currency: String, iso: Int) = if (currency.uppercase() in STRIPE_TWO_DECIMAL) 2 else iso

        /** Our minor units (ISO digits) to the amount Stripe expects. */
        fun toStripeAmount(minor: Long, currency: String): Long {
            val iso = Money.digits(currency)
            return shift(minor, iso, stripeDigits(currency, iso))
        }

        fun fromStripeAmount(amount: Long, currency: String): Long {
            val iso = Money.digits(currency)
            return shift(amount, stripeDigits(currency, iso), iso)
        }

        private fun shift(value: Long, from: Int, to: Int): Long {
            var v = value
            repeat(from - to) { v = Math.floorDiv(v + 5, 10) }
            repeat(to - from) { v *= 10 }
            return v
        }

        /**
         * Checks a `Stripe-Signature` header (t=…,v1=…): an HMAC-SHA256 of "t.payload" with the
         * endpoint's signing secret, within five minutes of now, to resist replays.
         */
        fun verifySignature(payload: String, header: String?, secret: String, now: Instant = Instant.now(), tolerance: Duration = Duration.ofMinutes(5)): Boolean {
            if (header.isNullOrBlank() || secret.isBlank()) return false
            val parts = header.split(",").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim() } }
            val timestamp = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull() ?: return false
            if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs() > tolerance) return false
            val expected = sign(secret, timestamp, payload)
            return parts.filter { it.first == "v1" }.any { MessageDigest.isEqual(it.second.toByteArray(), expected.toByteArray()) }
        }

        fun sign(secret: String, timestamp: Long, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
