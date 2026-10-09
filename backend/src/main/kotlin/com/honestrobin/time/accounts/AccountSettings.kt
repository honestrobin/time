// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.platform.features.Feature
import com.honestrobin.time.platform.features.Features
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** The account settings that domain rules depend on. */
data class AccountSettings(
    val id: UUID,
    val name: String,
    val timezone: ZoneId,
    val weekStart: DayOfWeek,
    val defaultCurrency: String,
    val roundingMinutes: Int,
    val roundingMode: String,
    /** On only while the account turned approvals on and the approvals switch is on (platform.features). */
    val approvalsEnabled: Boolean,
    val locale: String,
) {
    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(timezone))

    fun weekStartOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(weekStart))
}

@Component
class AccountSettingsRepository(private val dsl: DSLContext, private val features: Features) {
    fun get(accountId: UUID): AccountSettings {
        val r = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).fetchOne() ?: error("Account $accountId not visible")
        return AccountSettings(
            id = r.id, name = r.name, timezone = ZoneId.of(r.timezone), weekStart = DayOfWeek.of(r.weekStart.toInt()),
            defaultCurrency = r.defaultCurrency, roundingMinutes = r.timeRoundingMinutes.toInt(), roundingMode = r.timeRoundingMode,
            approvalsEnabled = r.approvalsEnabled && features.isOn(Feature.APPROVALS),
            locale = r.locale,
        )
    }
}
