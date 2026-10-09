// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.notifications

import com.honestrobin.time.auth.SessionService
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.AccountsRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.features.Feature
import com.honestrobin.time.platform.features.Features
import com.honestrobin.time.platform.mail.Mailer
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Weekly timesheet reminders (spec §2.1): on the first day of the account's week, from 09:00
 * local time, people whose previous week is incomplete get one email. "Incomplete" means below
 * their weekly capacity, or not submitted when approvals are on (the account's setting and the
 * approvals switch, platform.features).
 */
@Service
class TimesheetReminderService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val mailer: Mailer,
    private val props: HonestRobinProperties,
    private val clock: Clock,
    private val features: Features,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runDue(): Int {
        val accounts = tx.system {
            dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.TIMESHEET_REMINDERS_ENABLED.isTrue).and(ACCOUNTS.STATUS.eq("active")).fetch()
        }
        return accounts.sumOf { account ->
            runCatching { DbContext.forAccount(account.id) { tx.run { remindAccount(account) } } }
                .onFailure { log.error("Timesheet reminders failed for account {}", account.id, it) }
                .getOrDefault(0)
        }
    }

    /** Sends reminders for [account] if they are due now; returns the number of emails sent. */
    fun remindAccount(account: AccountsRecord, now: ZonedDateTime = ZonedDateTime.now(clock.withZone(ZoneId.of(account.timezone)))): Int {
        val weekStart = DayOfWeek.of(account.weekStart.toInt())
        val thisWeek = now.toLocalDate().with(TemporalAdjusters.previousOrSame(weekStart))
        if (now.dayOfWeek != weekStart || now.hour < 9 || account.lastTimesheetReminderWeek == thisWeek) return 0
        val lastWeek = thisWeek.minusWeeks(1)
        val tracked = DSL.coalesce(
            DSL.select(DSL.sum(TIME_ENTRIES.DURATION_SECONDS).cast(SQLDataType.BIGINT)).from(TIME_ENTRIES)
                .where(TIME_ENTRIES.MEMBERSHIP_ID.eq(MEMBERSHIPS.ID)).and(TIME_ENTRIES.SPENT_DATE.between(lastWeek, lastWeek.plusDays(6)))
                .asField<Long>(),
            0L,
        )
        val submitted = DSL.exists(
            DSL.selectOne().from(TIMESHEET_SUBMISSIONS).where(TIMESHEET_SUBMISSIONS.MEMBERSHIP_ID.eq(MEMBERSHIPS.ID))
                .and(TIMESHEET_SUBMISSIONS.WEEK_START_DATE.eq(lastWeek)).and(TIMESHEET_SUBMISSIONS.STATE.`in`("submitted", "approved")),
        )
        val people = dsl.select(USERS.EMAIL, MEMBERSHIPS.NAME, tracked, MEMBERSHIPS.WEEKLY_CAPACITY_SECONDS, DSL.field(submitted))
            .from(MEMBERSHIPS).join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
            .fetch()
        val approvals = account.approvalsEnabled && features.isOn(Feature.APPROVALS)
        var sent = 0
        people.forEach { p ->
            val incomplete = p.value3() < p.value4() || (approvals && p.value5() != true)
            if (incomplete) {
                mailer.send(
                    "timesheet-reminder", p.value1(), Locale.forLanguageTag(account.locale),
                    mapOf("name" to p.value2(), "account" to account.name, "week" to lastWeek.toString(), "link" to "${props.baseUrl}/week/$lastWeek"),
                    subjectArgs = arrayOf(lastWeek.toString()),
                )
                sent++
            }
        }
        dsl.update(ACCOUNTS).set(ACCOUNTS.LAST_TIMESHEET_REMINDER_WEEK, thisWeek).where(ACCOUNTS.ID.eq(account.id)).execute()
        return sent
    }
}

@Configuration
class JobsConfig {
    @Bean
    fun timesheetReminderTask(reminders: TimesheetReminderService): RecurringTask<Void> =
        Tasks.recurring("timesheet-reminders", FixedDelay.of(Duration.ofMinutes(15))).execute { _, _ -> reminders.runDue() }

    @Bean
    fun sessionCleanupTask(
        sessions: SessionService,
        limiter: com.honestrobin.time.platform.security.RateLimiter,
        devices: com.honestrobin.time.auth.DeviceAuthorizationService,
        auth: com.honestrobin.time.auth.AuthService,
    ): RecurringTask<Void> =
        Tasks.recurring("session-cleanup", FixedDelay.of(Duration.ofHours(6))).execute { _, _ ->
            sessions.purgeExpired()
            limiter.purge()
            devices.purgeExpired()
            auth.purgeOldLinks()
        }
}
