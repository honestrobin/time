// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// Sign-in is our own (sessions and API tokens); Spring's generated in-memory user is never used.
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class HonestRobinApplication

fun main(args: Array<String>) {
    runApplication<HonestRobinApplication>(*args)
}
