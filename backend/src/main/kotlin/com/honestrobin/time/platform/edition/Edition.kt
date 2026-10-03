// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.edition

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * One build, two editions. The edition may only switch the modules listed in
 * [EDITION_SPECIFIC_PACKAGES] (trust charter §1.2.3); EditionParityTest enforces this.
 */
enum class Edition {
    CLOUD,
    SELFHOST,
}

/** Packages allowed to depend on the edition. Everything else must behave identically. */
val EDITION_SPECIFIC_PACKAGES = listOf(
    "com.honestrobin.time.billing", // Paddle subscriptions
    "com.honestrobin.time.analytics", // PostHog
    "com.honestrobin.time.platform.edition", // the switch itself, marketing links, Honest Robin-operated provider credentials
)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["honestrobin.edition"], havingValue = "cloud")
annotation class CloudEditionOnly
