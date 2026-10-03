// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.harvest

import org.springframework.stereotype.Component

/** Builds a client for one Harvest account with the configured limits; waits scale down in tests. */
@Component
class HarvestClientFactory(private val settings: HarvestSettings) {
    fun create(token: String, accountId: String): HarvestClient {
        val sleeper = Sleeper { ms -> if (ms > 0) Thread.sleep((ms * settings.waitScale).toLong().coerceAtLeast(0)) }
        return HarvestClient(
            baseUrl = settings.apiBaseUrl,
            token = token,
            accountId = accountId,
            userAgent = settings.userAgent,
            general = RateLimiter(settings.generalLimit, settings.generalWindow, sleeper),
            reports = RateLimiter(settings.reportsLimit, settings.reportsWindow, sleeper),
            sleeper = sleeper,
        )
    }
}
