// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * argon2id with OWASP-recommended parameters (19 MiB, t=2, p=1). Each hash takes 19 MiB of memory
 * and real CPU time, and requests run on virtual threads that nothing else limits, so a burst of
 * sign-in attempts could use up the heap (security review, 4 October 2026). Only a few hashes run
 * at once; the rest wait their turn, and after [wait] are told to try again.
 */
@Component
class Passwords internal constructor(maxConcurrent: Int, private val wait: Duration) {
    constructor() : this(Runtime.getRuntime().availableProcessors().coerceIn(2, 8), Duration.ofSeconds(10))

    private val encoder = Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)
    private val slots = Semaphore(maxConcurrent, true)

    // Used to equalise timing when the user does not exist.
    private val dummyHash = checkNotNull(encoder.encode("honestrobin-timing-equaliser"))

    fun hash(raw: String): String = limited { checkNotNull(encoder.encode(raw)) }

    fun matches(raw: String, hash: String?): Boolean = limited {
        if (hash == null) {
            encoder.matches(raw, dummyHash)
            false
        } else {
            encoder.matches(raw, hash)
        }
    }

    internal fun <T> limited(block: () -> T): T {
        if (!slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "busy", "Too many people are signing in at once. Try again in a moment.")
        }
        try {
            return block()
        } finally {
            slots.release()
        }
    }
}
