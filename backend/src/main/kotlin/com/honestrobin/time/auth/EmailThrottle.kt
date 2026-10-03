// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.security.RateLimiter
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Stops the sign-in emails (magic link, password reset) being used to flood someone's inbox: only a
 * few go to one address within the window. Requests over the limit are dropped without an error, so
 * the response still never reveals whether an address has an account.
 */
@Component
class EmailThrottle(private val clock: Clock, private val limiter: RateLimiter) {
    private val window = Duration.ofMinutes(15)
    private val perAddress = 3

    /** True if a link email may go to [email] now; counts it if so (unless the request rolls back). */
    fun allow(email: String): Boolean = limiter.tryAcquire("link-mail:" + email.lowercase(), perAddress, window, Instant.now(clock))
}
