// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency
import kotlin.math.abs

/**
 * Properties of the time and money arithmetic that must hold for every input, not just the examples
 * someone thought of. A failure prints the smallest input that breaks the rule.
 */
class MoneyPropertyTest {
    private fun property(block: suspend () -> Unit) = runBlocking { block() }

    private val seconds = Arb.long(0L..2 * 86_400L)
    private val steps = Arb.element(6, 15, 30)
    private val modes = Arb.element("up", "nearest")
    private val rates = Arb.long(0L..1_000_000_00L)

    @Test
    fun `rounding lands on a step, moves time by less than a step and is stable when applied twice`() = property {
        checkAll(seconds, steps, modes) { s, minutes, mode ->
            val step = minutes * 60L
            val rounded = Money.roundSeconds(s, minutes, mode)
            assertThat(rounded % step).isZero()
            assertThat(abs(rounded - s)).isLessThan(step)
            if (mode == "up") assertThat(rounded).isGreaterThanOrEqualTo(s) else assertThat(abs(rounded - s) * 2).isLessThanOrEqualTo(step)
            assertThat(Money.roundSeconds(rounded, minutes, mode)).isEqualTo(rounded)
        }
    }

    @Test
    fun `rounding never reorders two durations, and rounding up is never below rounding to the nearest`() = property {
        checkAll(seconds, seconds, steps, modes) { a, b, minutes, mode ->
            if (a <= b) assertThat(Money.roundSeconds(a, minutes, mode)).isLessThanOrEqualTo(Money.roundSeconds(b, minutes, mode))
            assertThat(Money.roundSeconds(a, minutes, "up")).isGreaterThanOrEqualTo(Money.roundSeconds(a, minutes, "nearest"))
        }
    }

    @Test
    fun `no rounding means the exact duration`() = property {
        checkAll(seconds, modes) { s, mode -> assertThat(Money.roundSeconds(s, 0, mode)).isEqualTo(s) }
    }

    @Test
    fun `the amount for a duration is exact for whole hours and within half a minor unit otherwise`() = property {
        checkAll(seconds, rates) { s, rate ->
            val amount = Money.forDuration(s, rate)
            assertThat(abs(amount * 3600 - s * rate)).isLessThanOrEqualTo(1800)
            if (s % 3600 == 0L) assertThat(amount).isEqualTo(s / 3600 * rate)
        }
    }

    @Test
    fun `more time or a higher rate never costs less`() = property {
        checkAll(seconds, seconds, rates, rates) { a, b, r1, r2 ->
            if (a <= b) assertThat(Money.forDuration(a, r1)).isLessThanOrEqualTo(Money.forDuration(b, r1))
            if (r1 <= r2) assertThat(Money.forDuration(a, r1)).isLessThanOrEqualTo(Money.forDuration(a, r2))
        }
    }

    /** Why invoice totals must come from one rule: per-entry amounts and the amount of the summed time can differ by a unit. */
    @Test
    fun `splitting time into two entries changes the amount by at most one minor unit`() = property {
        checkAll(seconds, seconds, rates) { a, b, rate ->
            val together = Money.forDuration(a + b, rate)
            val apart = Money.forDuration(a, rate) + Money.forDuration(b, rate)
            assertThat(abs(together - apart)).isLessThanOrEqualTo(1)
        }
    }

    @Test
    fun `minor units survive a round trip through decimals in every currency in the world`() = property {
        val currencies = Currency.getAvailableCurrencies().map { it.currencyCode }.sorted()
        assertThat(currencies).contains("USD", "EUR", "JPY", "KWD", "INR", "BRL")
        checkAll(Arb.element(currencies), Arb.long(-1_000_000_000_000L..1_000_000_000_000L)) { currency, minor ->
            val decimal = Money.fromMinor(minor, currency)
            assertThat(decimal.scale()).isEqualTo(Money.digits(currency))
            assertThat(Money.toMinor(decimal, currency)).isEqualTo(minor)
        }
    }

    @Test
    fun `a decimal amount becomes the nearest minor unit, half going up, whatever the currency's precision`() = property {
        checkAll(Arb.element("JPY", "KRW", "USD", "EUR", "INR", "KWD", "BHD"), Arb.long(0L..1_000_000_000L), Arb.int(0..9999)) { currency, whole, tenThousandths ->
            val amount = BigDecimal.valueOf(whole).add(BigDecimal.valueOf(tenThousandths.toLong(), 4))
            val minor = Money.toMinor(amount, currency)
            val exact = amount.movePointRight(Money.digits(currency))
            assertThat(BigDecimal.valueOf(minor).subtract(exact).abs()).isLessThanOrEqualTo(BigDecimal("0.5"))
            if (exact.subtract(BigDecimal.valueOf(minor)).compareTo(BigDecimal("0.5")) == 0) error("half must round up: $amount $currency -> $minor")
        }
    }

    @Test
    fun `units times a unit price is within half a minor unit, and one unit costs the unit price`() = property {
        checkAll(Arb.long(0L..1_000_000L), Arb.long(0L..10_000_00L)) { hundredths, price ->
            val units = BigDecimal.valueOf(hundredths, 2)
            val total = Money.times(units, price)
            assertThat(BigDecimal.valueOf(total).subtract(units.multiply(BigDecimal.valueOf(price))).abs()).isLessThanOrEqualTo(BigDecimal("0.5"))
            assertThat(Money.times(BigDecimal.ONE, price)).isEqualTo(price)
            assertThat(Money.times(BigDecimal.ZERO, price)).isZero()
        }
    }
}
