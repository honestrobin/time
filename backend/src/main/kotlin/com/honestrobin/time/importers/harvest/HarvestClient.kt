// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.harvest

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayDeque
import kotlin.random.Random

/** Pauses the current thread; injectable so tests can compress waits. */
fun interface Sleeper {
    fun sleep(millis: Long)
}

/**
 * Sliding-window limiter: at most [limit] calls in any [window]. Harvest allows 100 requests per
 * 15 seconds for general endpoints and 100 per 15 minutes for the Reports API (verified 2026-09-30).
 */
class RateLimiter(private val limit: Int, private val window: Duration, private val sleeper: Sleeper, private val now: () -> Long = System::currentTimeMillis) {
    private val calls = ArrayDeque<Long>()

    @Synchronized
    fun acquire() {
        while (true) {
            val t = now()
            while (calls.isNotEmpty() && calls.first() <= t - window.toMillis()) calls.removeFirst()
            if (calls.size < limit) {
                calls.addLast(t)
                return
            }
            sleeper.sleep((calls.first() + window.toMillis() - t).coerceAtLeast(1))
        }
    }
}

class HarvestApiException(val status: Int, message: String) : RuntimeException(message)

/** Harvest reported a 4xx that retrying won't fix (e.g. revoked token). */
class HarvestAuthException(message: String) : RuntimeException(message)

/**
 * Minimal Harvest API v2 client: bearer token + Harvest-Account-Id + descriptive User-Agent,
 * cursor pagination via `links.next`, 429 handling with Retry-After, and exponential backoff
 * with jitter for server and network errors.
 */
class HarvestClient(
    private val baseUrl: String,
    private val token: String,
    private val accountId: String,
    private val userAgent: String,
    private val general: RateLimiter,
    private val reports: RateLimiter,
    private val sleeper: Sleeper,
    private val maxAttempts: Int = 6,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    var requestCount = 0
        private set

    val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun url(path: String, params: Map<String, Any?> = emptyMap()): String {
        val query = params.filterValues { it != null }.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        return baseUrl.trimEnd('/') + path + if (query.isNotEmpty()) "?$query" else ""
    }

    fun get(url: String): JsonNode {
        val isReport = url.contains("/reports/")
        var attempt = 0
        while (true) {
            attempt++
            (if (isReport) reports else general).acquire()
            requestCount++
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $token")
                .header("Harvest-Account-Id", accountId)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET().build()
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw HarvestApiException(0, "Harvest could not be reached: ${e.message}")
                backoff(attempt, "network error: ${e.message}")
                continue
            }
            when (val code = response.statusCode()) {
                in 200..299 -> return mapper.readTree(response.body())
                429 -> {
                    // Throttled: wait as instructed. Doesn't count as a failed attempt.
                    val wait = response.headers().firstValue("Retry-After").map { it.toLongOrNull() }.orElse(null) ?: 15
                    log.info("Harvest rate limit hit; waiting {} s", wait)
                    sleeper.sleep(wait * 1000)
                    attempt--
                }
                401, 403 -> throw HarvestAuthException("Harvest rejected the token (HTTP $code). Check that it is valid and has access to account $accountId.")
                in 500..599 -> {
                    if (attempt >= maxAttempts) throw HarvestApiException(code, "Harvest returned HTTP $code for $url")
                    backoff(attempt, "HTTP $code")
                }
                else -> throw HarvestApiException(code, "Harvest returned HTTP $code for $url: ${response.body().take(300)}")
            }
        }
    }

    private fun backoff(attempt: Int, why: String) {
        val base = 1000L * (1L shl (attempt - 1).coerceAtMost(6))
        val wait = (base + Random.nextLong(base / 2 + 1)).coerceAtMost(60_000)
        log.info("Harvest request failed ({}); retry {} in {} ms", why, attempt, wait)
        sleeper.sleep(wait)
    }

    /** Fetches one page; [key] is the array property (e.g. "time_entries"). */
    fun <T> page(url: String, key: String, type: Class<T>): HPage<T> {
        val node = get(url)
        val raw = node[key]?.toList() ?: emptyList()
        val next = node["links"]?.get("next")?.takeIf { !it.isNull }?.asText()
        return HPage(raw.map { mapper.treeToValue(it, type) }, next, raw)
    }

    /** All pages from [firstUrl], following `links.next` as the docs require. */
    fun <T> all(firstUrl: String, key: String, type: Class<T>): List<T> {
        val out = mutableListOf<T>()
        var url: String? = firstUrl
        while (url != null) {
            val p = page(url, key, type)
            out += p.items
            url = p.nextUrl
        }
        return out
    }

    fun <T> single(path: String, type: Class<T>): T = mapper.treeToValue(get(url(path)), type)

    /** Downloads a receipt. Receipt URLs are pre-signed, so no Harvest headers are sent. */
    fun download(url: String): Pair<ByteArray, String?> {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                if (res.statusCode() in 200..299) return res.body() to res.headers().firstValue("Content-Type").orElse(null)
                if (res.statusCode() < 500 || attempt >= 3) throw HarvestApiException(res.statusCode(), "Receipt download failed with HTTP ${res.statusCode()}")
            } catch (e: IOException) {
                if (attempt >= 3) throw HarvestApiException(0, "Receipt download failed: ${e.message}")
            }
            backoff(attempt, "receipt download")
        }
    }
}
