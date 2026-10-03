// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.harvest

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "honestrobin.harvest")
data class HarvestSettings(
    val apiBaseUrl: String = "https://api.harvestapp.com/v2",
    val idBaseUrl: String = "https://id.getharvest.com",
    /** Harvest requires the app name plus a link or contact email. */
    val userAgent: String = "Honest Robin Time importer (https://github.com/honestrobin/time)",
    /** OAuth2 app credentials. Without them, admins paste a personal access token instead. */
    val oauthClientId: String = "",
    val oauthClientSecret: String = "",
    val generalLimit: Int = 100,
    val generalWindow: Duration = Duration.ofSeconds(15),
    val reportsLimit: Int = 100,
    val reportsWindow: Duration = Duration.ofMinutes(15),
    /** Multiplies every wait (backoff, Retry-After). Tests use a small value. */
    val waitScale: Double = 1.0,
    /** Days treated as "recent work" in phase 2. */
    val recentDays: Long = 90,
    val syncInterval: Duration = Duration.ofMinutes(15),
    val maxSyncDays: Long = 30,
    /** Records per page; Harvest allows up to 2000. Tests use small pages to exercise paging. */
    val pageSize: Int = 2000,
    /** Run imports in the request thread instead of the job scheduler. Tests only. */
    val runInline: Boolean = false,
) {
    val oauthEnabled get() = oauthClientId.isNotBlank() && oauthClientSecret.isNotBlank()
}
