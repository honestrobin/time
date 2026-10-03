// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.notifications

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZoneId
import java.time.ZonedDateTime

class TimesheetReminderTest : IntegrationTest() {
    @Autowired
    lateinit var reminders: TimesheetReminderService

    @Test
    fun `reminds people with incomplete weeks once, on the first morning of the week`() {
        val admin = signup()
        admin.patch("/api/v1/account", mapOf("timesheet_reminders_enabled" to true)).expect(200)
        val busy = invite(admin, extra = mapOf("weekly_capacity_seconds" to 3600))
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), members = listOf(busy)).id()
        busy.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 3600)).expect(201)

        val zone = ZoneId.of("Europe/Zagreb")
        fun run(at: ZonedDateTime) = DbContext.forAccount(admin.accountId!!) {
            tx.run { reminders.remindAccount(dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(admin.accountId)).fetchOne()!!, at) }
        }
        assertThat(run(ZonedDateTime.of(2026, 9, 21, 8, 0, 0, 0, zone))).isZero() // Monday, before 09:00
        assertThat(run(ZonedDateTime.of(2026, 9, 22, 10, 0, 0, 0, zone))).isZero() // Tuesday
        assertThat(run(ZonedDateTime.of(2026, 9, 21, 9, 30, 0, 0, zone))).isEqualTo(1) // only the admin, who tracked nothing
        assertThat(mail.lastTo(admin.email!!).subject).contains("2026-09-14")
        assertThat(mail.to(busy.email!!).filter { it.template == "timesheet-reminder" }).isEmpty()
        assertThat(run(ZonedDateTime.of(2026, 9, 21, 11, 0, 0, 0, zone))).isZero() // already sent this week
    }
}
