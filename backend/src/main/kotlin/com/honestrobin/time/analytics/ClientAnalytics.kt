// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.analytics

import com.honestrobin.time.platform.edition.CloudEditionOnly
import com.honestrobin.time.platform.security.CspContributor
import com.honestrobin.time.platform.security.CspSources
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** What the web app needs to send page views: PostHog's project key (public by design) and host. */
data class ClientAnalyticsConfig(val posthogKey: String, val posthogHost: String)

/** Page views from the web app (spec §2: PostHog, cloud edition only). Self-host: none. */
fun interface ClientAnalytics {
    fun config(): ClientAnalyticsConfig?
}

@Configuration
class ClientAnalyticsSetup {
    @Bean
    @CloudEditionOnly
    fun cloudClientAnalytics(
        @Value("\${honestrobin.posthog.api-key:}") apiKey: String,
        @Value("\${honestrobin.posthog.host:https://eu.i.posthog.com}") host: String,
    ): ClientAnalytics {
        val config = apiKey.takeIf { it.isNotBlank() }?.let { ClientAnalyticsConfig(it, host.trimEnd('/')) }
        return ClientAnalytics { config }
    }

    @Bean
    @ConditionalOnMissingBean(ClientAnalytics::class)
    fun noClientAnalytics(): ClientAnalytics = ClientAnalytics { null }

    /** The browser may send page views to PostHog, and nowhere else new. */
    @Bean
    fun postHogCsp(analytics: ClientAnalytics): CspContributor =
        CspContributor { analytics.config()?.let { CspSources(connect = listOf(it.posthogHost)) } ?: CspSources() }
}
