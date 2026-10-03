// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Real time plus an offset that tests can advance, so timers can be tested without sleeping. */
@Component
@Primary
class MutableClock : Clock() {
    @Volatile
    private var offset: Duration = Duration.ZERO

    fun advance(d: Duration) {
        offset = offset.plus(d)
    }

    /** Jumps to [instant] and returns the previous offset, to hand back to [restore] when the test ends. */
    fun travelTo(instant: Instant): Duration {
        val previous = offset
        offset = Duration.between(Instant.now(), instant)
        return previous
    }

    fun restore(previous: Duration) {
        offset = previous
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = object : Clock() {
        override fun getZone() = zone
        override fun withZone(z: ZoneId) = this@MutableClock.withZone(z)
        override fun instant(): Instant = this@MutableClock.instant()
    }

    override fun instant(): Instant = Instant.now().plus(offset)
}
