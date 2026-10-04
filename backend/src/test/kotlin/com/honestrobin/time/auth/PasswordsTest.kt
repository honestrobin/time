// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.web.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch

/** Security review, 4 October 2026: a burst of sign-ins can't use up memory hashing passwords. */
class PasswordsTest {
    @Test
    fun `only a few hashes run at once, and the rest wait, then are told to try again`() {
        val passwords = Passwords(maxConcurrent = 1, wait = Duration.ofMillis(200))
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val busy = Thread.ofVirtual().start { passwords.limited { holding.countDown(); release.await() } }
        holding.await()
        assertThatThrownBy { passwords.hash("correct horse battery") }
            .isInstanceOf(ApiException::class.java).hasMessageContaining("Try again in a moment")
        release.countDown()
        busy.join()
        // Once the slot is free, hashing works again.
        assertThat(passwords.matches("correct horse battery", passwords.hash("correct horse battery"))).isTrue()
    }
}
