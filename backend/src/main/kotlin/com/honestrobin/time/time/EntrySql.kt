// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.db.Tables.TIME_ENTRIES
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant

/**
 * Per-entry durations and amounts in SQL, for reports and budgets over many entries. Integer
 * arithmetic, with exactly the rules of [com.honestrobin.time.platform.Money.roundSeconds] and
 * [com.honestrobin.time.platform.Money.forDuration] (rounding half up), because numeric
 * arithmetic on a million rows costs seconds. `EntrySqlTest` checks the two agree.
 */
object EntrySql {
    private val stored: Field<Int> = TIME_ENTRIES.DURATION_SECONDS

    /** Tracked seconds; with [now], a running timer counts up to then, capped at 24 hours. */
    fun seconds(now: Instant? = null): Field<Int> {
        if (now == null) return stored
        val elapsed = DSL.field("greatest(0, extract(epoch from ({0} - {1}))::bigint)::integer", SQLDataType.INTEGER, DSL.value(now, TIME_ENTRIES.TIMER_STARTED_AT), TIME_ENTRIES.TIMER_STARTED_AT)
        return DSL.`when`(TIME_ENTRIES.TIMER_STARTED_AT.isNull, stored).otherwise(DSL.least(DSL.inline(86_400), stored.plus(elapsed)))
    }

    /** [seconds] rounded with the account's rounding: up to, or to the nearest, step. */
    fun rounded(seconds: Field<Int>, roundingMinutes: Int, mode: String): Field<Int> {
        val step = roundingMinutes * 60
        if (step <= 0) return seconds
        val offset = if (mode == "nearest") step / 2 else step - 1
        return seconds.plus(DSL.inline(offset)).div(DSL.inline(step)).mul(DSL.inline(step))
    }

    /** [seconds] × [ratePerHour], half up, in minor units. */
    fun amount(seconds: Field<Int>, ratePerHour: Field<Long>): Field<Long> =
        seconds.cast(SQLDataType.BIGINT).mul(ratePerHour).plus(DSL.inline(1800L)).div(DSL.inline(3600L))

    /** The billable amount of an entry: rounded duration × its rate snapshot, zero when not billable. */
    fun billableAmount(rounded: Field<Int>): Field<Long> =
        DSL.`when`(TIME_ENTRIES.BILLABLE.isTrue, amount(rounded, TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT)).otherwise(DSL.inline(0L))

    /** Internal cost: tracked (unrounded) duration × the person's cost rate snapshot. */
    fun cost(seconds: Field<Int>): Field<Long> = amount(seconds, TIME_ENTRIES.COST_RATE_SNAPSHOT)
}
