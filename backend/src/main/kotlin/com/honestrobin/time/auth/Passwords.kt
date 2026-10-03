// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component

/** argon2id with OWASP-recommended parameters (19 MiB, t=2, p=1). */
@Component
class Passwords {
    private val encoder = Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)

    // Used to equalise timing when the user does not exist.
    private val dummyHash = encoder.encode("honestrobin-timing-equaliser")

    fun hash(raw: String): String = encoder.encode(raw)

    fun matches(raw: String, hash: String?): Boolean =
        if (hash == null) {
            encoder.matches(raw, dummyHash)
            false
        } else {
            encoder.matches(raw, hash)
        }
}
