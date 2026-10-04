// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.security.RateLimiter
import com.honestrobin.time.platform.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Slows down credential stuffing: limits failed attempts (passwords and two-factor codes) per email
 * and per IP within a window, counted in the database so every server of an instance agrees.
 *
 * Wrong two-factor codes count per person in a bucket of their own, which a correct password
 * doesn't reset: otherwise anyone who has the password could sign in again and again for more
 * guesses at the code.
 */
@Component
class LoginThrottle(private val limiter: RateLimiter) {
    private val window = Duration.ofMinutes(15)
    private val day = Duration.ofDays(1)
    private val perKeyLimit = mapOf("email" to 10, "ip" to 50)

    fun check(email: String, ip: String?) {
        keys(email, ip).forEach { (kind, key) ->
            if (limiter.count(key, window) >= perKeyLimit.getValue(kind)) throw tooMany()
        }
    }

    /** Counts a failure; it stands even though the request that failed rolls back. */
    fun failed(email: String, ip: String?) {
        keys(email, ip).forEach { (_, key) -> limiter.recordAlways(key) }
    }

    fun succeeded(email: String) {
        limiter.clear("login:email:" + email.lowercase())
    }

    /** Before checking a two-factor code for [challenge] (its id) of [userId]. */
    fun checkSecondFactor(userId: UUID, challenge: UUID, ip: String?) {
        val user = secondFactorKey(userId)
        if (limiter.count(user, window) >= SECOND_FACTOR_PER_WINDOW || limiter.count(user, day) >= SECOND_FACTOR_PER_DAY) throw tooMany()
        ip?.let { if (limiter.count("login:ip:$it", window) >= perKeyLimit.getValue("ip")) throw tooMany() }
        if (limiter.count(challengeKey(challenge), day) >= WRONG_CODES_PER_CHALLENGE) {
            throw ApiException(HttpStatus.BAD_REQUEST, "challenge_expired", "Too many wrong codes. Sign in again.")
        }
    }

    /** Counts a wrong code; returns how many this person has had in the last day. */
    fun failedSecondFactor(userId: UUID, challenge: UUID, ip: String?): Int {
        limiter.recordAlways(secondFactorKey(userId))
        limiter.recordAlways(challengeKey(challenge))
        ip?.let { limiter.recordAlways("login:ip:$it") }
        return limiter.count(secondFactorKey(userId), day)
    }

    fun succeededSecondFactor(userId: UUID) {
        limiter.clear(secondFactorKey(userId))
    }

    private fun keys(email: String, ip: String?) = listOfNotNull("email" to "login:email:" + email.lowercase(), ip?.let { "ip" to "login:ip:$it" })

    private fun secondFactorKey(userId: UUID) = "login:2fa:$userId"

    private fun challengeKey(challenge: UUID) = "login:2fa-challenge:$challenge"

    private fun tooMany() =
        ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "Too many sign-in attempts. Try again in 15 minutes or use a magic link.")

    companion object {
        const val SECOND_FACTOR_PER_WINDOW = 10
        const val SECOND_FACTOR_PER_DAY = 50
        const val WRONG_CODES_PER_CHALLENGE = 5

        /** After this many wrong codes in a day, the person is told: someone has their password. */
        const val SECOND_FACTOR_NOTICE_AT = 5
    }
}
