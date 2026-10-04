// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.analytics

import com.honestrobin.time.platform.edition.CloudEditionOnly
import tools.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** Product funnel events (spec §17 AT-6.5). Cloud edition only; self-host sends nothing. */
fun interface Analytics {
    fun capture(event: String, distinctId: UUID, properties: Map<String, Any?>)
}

object NoopAnalytics : Analytics {
    override fun capture(event: String, distinctId: UUID, properties: Map<String, Any?>) = Unit
}

/** PostHog capture API, EU region. No personal data beyond the opaque user/account ids. */
class PostHogAnalytics(private val apiKey: String, private val host: String, private val mapper: ObjectMapper) : Analytics {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun capture(event: String, distinctId: UUID, properties: Map<String, Any?>) {
        val body = mapper.writeValueAsString(
            mapOf("api_key" to apiKey, "event" to event, "distinct_id" to distinctId.toString(), "properties" to properties, "timestamp" to Instant.now().toString()),
        )
        executor.submit {
            runCatching {
                client.send(
                    HttpRequest.newBuilder(URI.create("$host/capture/")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            }.onFailure { log.debug("PostHog capture failed", it) }
        }
    }
}

@Configuration
class AnalyticsConfig {
    @Bean
    @CloudEditionOnly
    fun cloudAnalytics(
        @Value("\${honestrobin.posthog.api-key:}") apiKey: String,
        @Value("\${honestrobin.posthog.host:https://eu.i.posthog.com}") host: String,
        mapper: ObjectMapper,
    ): Analytics = if (apiKey.isBlank()) NoopAnalytics else PostHogAnalytics(apiKey, host, mapper)

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(Analytics::class)
    fun noopAnalytics(): Analytics = NoopAnalytics
}
