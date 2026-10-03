// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.crypto

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238) as authenticator apps make them: HMAC-SHA1, 30-second
 * steps, six digits. Codes from the step before and after are accepted too, for clocks that drift.
 */
object Totp {
    private const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private val random = SecureRandom()

    /** A new 160-bit secret, base32 as apps expect it. */
    fun newSecret(): String = Base32.encode(ByteArray(20).also(random::nextBytes))

    /** The `otpauth://` link an authenticator app reads from the QR code. */
    fun uri(secret: String, account: String, issuer: String): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    fun step(at: Instant): Long = at.epochSecond / STEP_SECONDS

    fun code(secret: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(Base32.decode(secret), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /**
     * The step [code] belongs to, or null if it doesn't match. Steps up to [lastUsed] are refused,
     * so a code can't be used twice.
     */
    fun verify(secret: String, code: String, at: Instant, lastUsed: Long?): Long? {
        val digits = code.filter(Char::isDigit)
        if (digits.length != DIGITS) return null
        val now = step(at)
        return (now - 1..now + 1).firstOrNull { s ->
            (lastUsed == null || s > lastUsed) && MessageDigest.isEqual(code(secret, s).toByteArray(), digits.toByteArray())
        }
    }
}

/** RFC 4648 base32 without padding, as authenticator apps use it. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.uppercase().filter { it != '=' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Not base32: $c" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
