// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class TimeConfig {
    /** All "now" in domain code comes from this clock so timers can be tested deterministically. */
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun clock(): Clock = Clock.systemUTC()
}
