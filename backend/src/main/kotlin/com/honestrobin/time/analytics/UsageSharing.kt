// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.analytics

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Whether an account shares how it uses Time, such as the steps it reaches (the Robin's Code,
 * article 5; decision record 0028). Off until the account turns it on. The setting to turn it on
 * isn't built yet, so for now no account shares anything and the funnel sends nothing.
 */
fun interface UsageSharing {
    fun sharedBy(accountId: UUID): Boolean
}

@Configuration
class UsageSharingSetup {
    @Bean
    @ConditionalOnMissingBean(UsageSharing::class)
    fun nobodySharesYet(): UsageSharing = UsageSharing { false }
}
