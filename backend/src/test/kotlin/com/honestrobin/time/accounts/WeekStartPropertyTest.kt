// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Weeks start on different days around the world; every date must fall in exactly one week. */
class WeekStartPropertyTest {
    private fun settings(weekStart: DayOfWeek) = AccountSettings(
        id = UUID.randomUUID(), name = "Any", timezone = ZoneOffset.UTC, weekStart = weekStart, defaultCurrency = "USD",
        roundingMinutes = 0, roundingMode = "up", approvalsEnabled = false, locale = "en",
    )

    @Test
    fun `every date belongs to the week that starts on the account's first day, at most six days earlier`() = runBlocking<Unit> {
        // 1970 to 2100, leap years and century boundaries included.
        checkAll(Arb.long(0L..47_480L), Arb.enum<DayOfWeek>()) { epochDay, weekStart ->
            val date = LocalDate.ofEpochDay(epochDay)
            val s = settings(weekStart)
            val start = s.weekStartOf(date)
            assertThat(start.dayOfWeek).isEqualTo(weekStart)
            assertThat(ChronoUnit.DAYS.between(start, date)).isBetween(0, 6)
            assertThat(s.weekStartOf(start)).isEqualTo(start)
            (0L..6L).forEach { assertThat(s.weekStartOf(start.plusDays(it))).isEqualTo(start) }
            assertThat(s.weekStartOf(start.plusDays(7))).isEqualTo(start.plusDays(7))
            assertThat(s.weekStartOf(start.minusDays(1))).isEqualTo(start.minusDays(7))
        }
    }
}
