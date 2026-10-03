// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import java.security.SecureRandom
import java.util.UUID

/** UUIDv7 (RFC 9562) generated in the application, for batch inserts that need ids up front. */
object Ids {
    private val random = SecureRandom()

    fun v7(): UUID {
        val ms = System.currentTimeMillis()
        val bytes = ByteArray(16).also(random::nextBytes)
        for (i in 0 until 6) bytes[i] = (ms shr (40 - 8 * i)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xff)
        for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xff)
        return UUID(msb, lsb)
    }
}
