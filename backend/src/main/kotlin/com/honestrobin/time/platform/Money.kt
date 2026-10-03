// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/** Money is always integer minor units plus an ISO 4217 currency; durations are integer seconds. */
object Money {
    /** Amount for [seconds] at [ratePerHour] (minor units per hour), rounded half-up to minor units. */
    fun forDuration(seconds: Long, ratePerHour: Long): Long =
        BigDecimal.valueOf(seconds).multiply(BigDecimal.valueOf(ratePerHour))
            .divide(BigDecimal.valueOf(3600), 0, RoundingMode.HALF_UP).toLong()

    /**
     * Applies the account's rounding for display, budgets and invoicing (never storage): up to, or to
     * the nearest, step of [roundingMinutes] (0 = exact). Matches Harvest's time rounding options.
     */
    fun roundSeconds(seconds: Long, roundingMinutes: Int, mode: String = "up"): Long {
        if (roundingMinutes <= 0 || seconds == 0L) return seconds
        val step = roundingMinutes * 60L
        return if (mode == "nearest") ((seconds + step / 2) / step) * step else ((seconds + step - 1) / step) * step
    }

    fun digits(currency: String): Int = Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)

    /** Converts a decimal amount (e.g. Harvest's 123.45) to minor units. */
    fun toMinor(amount: BigDecimal, currency: String): Long =
        amount.setScale(digits(currency), RoundingMode.HALF_UP).movePointRight(digits(currency)).toLong()

    fun fromMinor(minor: Long, currency: String): BigDecimal = BigDecimal.valueOf(minor, digits(currency))

    /** units × unit price, half-up. [units] has up to two decimals. */
    fun times(units: BigDecimal, unitPrice: Long): Long =
        units.multiply(BigDecimal.valueOf(unitPrice)).setScale(0, RoundingMode.HALF_UP).toLong()
}
