// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.security.RateLimiter
import com.honestrobin.time.platform.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Slows down credential stuffing: limits failed attempts (passwords and two-factor codes) per email
 * and per IP within a window, counted in the database so every server of an instance agrees.
 */
@Component
class LoginThrottle(private val limiter: RateLimiter) {
    private val window = Duration.ofMinutes(15)
    private val perKeyLimit = mapOf("email" to 10, "ip" to 50)

    fun check(email: String, ip: String?) {
        keys(email, ip).forEach { (kind, key) ->
            if (limiter.count(key, window) >= perKeyLimit.getValue(kind)) {
                throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "Too many sign-in attempts. Try again in 15 minutes or use a magic link.")
            }
        }
    }

    /** Counts a failure; it stands even though the request that failed rolls back. */
    fun failed(email: String, ip: String?) {
        keys(email, ip).forEach { (_, key) -> limiter.recordAlways(key) }
    }

    fun succeeded(email: String) {
        limiter.clear("login:email:" + email.lowercase())
    }

    private fun keys(email: String, ip: String?) = listOfNotNull("email" to "login:email:" + email.lowercase(), ip?.let { "ip" to "login:ip:$it" })
}
