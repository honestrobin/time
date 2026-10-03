// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Tokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** A URL-safe random token with [bytes] bytes of entropy, optionally prefixed (e.g. `hrt_` for API tokens). */
    fun generate(bytes: Int = 32, prefix: String = ""): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return prefix + encoder.encodeToString(buf)
    }

    fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
