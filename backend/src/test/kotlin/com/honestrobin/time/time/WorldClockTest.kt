// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Accounts live in every time zone. These tests pin the rules that depend on where an account is:
 * which day an entry belongs to, what a timer counts across a daylight-saving change or midnight,
 * and which calendar month a monthly budget covers.
 */
class WorldClockTest : IntegrationTest() {
    private var restore: Duration? = null

    private fun at(instant: String) {
        val previous = clock.travelTo(Instant.parse(instant))
        if (restore == null) restore = previous
    }

    @AfterEach
    fun backToNow() {
        restore?.let(clock::restore)
        restore = null
    }

    private fun tracked(admin: TestClient): Pair<UUID, UUID> {
        val task = createTask(admin)
        return createProject(admin, taskIds = listOf(task)).id() to task
    }

    @Test
    fun `a timer running through a daylight-saving change counts elapsed time, not the clock on the wall`() {
        // zone, an instant 30 minutes before its clocks change in 2026
        val changes = listOf(
            "America/New_York" to "2026-03-08T06:30:00Z", // clocks go forward
            "America/New_York" to "2026-11-01T05:30:00Z", // clocks go back
            "Europe/Berlin" to "2026-03-29T00:30:00Z",
            "Australia/Sydney" to "2026-04-04T15:30:00Z", // southern hemisphere, clocks go back
            "Australia/Lord_Howe" to "2026-04-04T14:30:00Z", // a 30-minute change
        )
        for ((zone, start) in changes) {
            val startInstant = Instant.parse(start)
            val endInstant = startInstant.plus(Duration.ofHours(2))
            val rules = ZoneId.of(zone).rules
            assertThat(rules.getOffset(startInstant)).describedAs("$zone must change its clocks within the two hours after $start").isNotEqualTo(rules.getOffset(endInstant))

            at(start)
            val admin = signup(timezone = zone)
            val (project, task) = tracked(admin)
            val entry = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task)).expect(201)
            assertThat(entry["spent_date"].asText()).isEqualTo(startInstant.atZone(ZoneId.of(zone)).toLocalDate().toString())

            clock.advance(Duration.ofHours(2))
            val stopped = admin.post("/api/v1/time_entries/${entry.id()}/stop").expect(200)
            assertThat(stopped["duration_seconds"].asLong()).describedAs("$zone from $start").isEqualTo(7200L)
            assertThat(stopped["spent_date"].asText()).isEqualTo(entry["spent_date"].asText())
            // The wall-clock times are kept for reference, even though they are not two hours apart.
            assertThat(stopped["start_time"].asText()).startsWith(startInstant.atZone(ZoneId.of(zone)).toLocalTime().toString().take(5))
            assertThat(stopped["end_time"].asText()).startsWith(endInstant.atZone(ZoneId.of(zone)).toLocalTime().toString().take(5))
            backToNow()
        }
    }

    @Test
    fun `the same moment is a different day in different places, and each account works in its own day and week`() {
        val moment = Instant.parse("2026-06-15T11:00:00Z")
        at(moment.toString())
        // +14:00, -11:00, +05:45, -02:30, and two ordinary ones; with weeks starting on different days.
        val places = listOf(
            Triple("Pacific/Kiritimati", 1, "2026-06-16"),
            Triple("Pacific/Pago_Pago", 7, "2026-06-15"),
            Triple("Asia/Kathmandu", 7, "2026-06-15"),
            Triple("America/St_Johns", 7, "2026-06-15"),
            Triple("Asia/Riyadh", 6, "2026-06-15"),
            Triple("Asia/Tokyo", 1, "2026-06-15"),
        )
        for ((zone, weekStart, expectedDay) in places) {
            assertThat(moment.atZone(ZoneId.of(zone)).toLocalDate().toString()).isEqualTo(expectedDay)
            val admin = signup(timezone = zone, weekStart = weekStart)
            val (project, task) = tracked(admin)
            val entry = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task)).expect(201)
            assertThat(entry["spent_date"].asText()).describedAs(zone).isEqualTo(expectedDay)

            val week = admin.get("/api/v1/timesheets/week").expect(200)
            val expectedStart = LocalDate.parse(expectedDay).with(TemporalAdjusters.previousOrSame(DayOfWeek.of(weekStart)))
            assertThat(week["week_start"].asText()).describedAs("$zone, week starts on ${DayOfWeek.of(weekStart)}").isEqualTo(expectedStart.toString())
            assertThat(week["days"].map { it.asText() }).hasSize(7).contains(expectedDay)

            // Yesterday here may still be today somewhere else; a timer only starts on the account's own today.
            admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to LocalDate.parse(expectedDay).minusDays(1).toString()))
                .expectError(422, "validation_failed")
        }
    }

    @Test
    fun `a timer that runs past midnight keeps the day it started on and can still be edited`() {
        at("2026-05-20T14:30:00Z") // 23:30 in Tokyo
        val admin = signup(timezone = "Asia/Tokyo")
        val (project, task) = tracked(admin)
        val entry = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task)).expect(201)
        assertThat(entry["spent_date"].asText()).isEqualTo("2026-05-20")
        assertThat(entry["start_time"].asText()).startsWith("23:30")

        clock.advance(Duration.ofHours(1))
        val stopped = admin.post("/api/v1/time_entries/${entry.id()}/stop").expect(200)
        assertThat(stopped["duration_seconds"].asLong()).isEqualTo(3600L)
        assertThat(stopped["spent_date"].asText()).isEqualTo("2026-05-20")
        assertThat(stopped["end_time"].asText()).startsWith("00:30")

        // The entry now ends "before" it starts on the clock. It must stay editable.
        val noted = admin.patch("/api/v1/time_entries/${entry.id()}", mapOf("notes" to "Late release")).expect(200)
        assertThat(noted["duration_seconds"].asLong()).isEqualTo(3600L)
        val corrected = admin.patch("/api/v1/time_entries/${entry.id()}", mapOf("duration_seconds" to 5400)).expect(200)
        assertThat(corrected["duration_seconds"].asLong()).isEqualTo(5400L)
        assertThat(corrected["spent_date"].asText()).isEqualTo("2026-05-20")
    }

    @Test
    fun `a monthly budget covers the calendar month where the account is`() {
        at("2026-07-31T23:30:00Z") // already 1 August in Tokyo, still 31 July in Los Angeles
        for ((zone, month) in listOf("Asia/Tokyo" to "2026-08-01", "America/Los_Angeles" to "2026-07-01")) {
            val admin = signup(timezone = zone)
            val task = createTask(admin)
            val project = createProject(
                admin, taskIds = listOf(task),
                extra = mapOf("budget_by" to "project", "budget_seconds" to 36_000, "budget_is_monthly" to true),
            ).id()
            val today = Instant.parse("2026-07-31T23:30:00Z").atZone(ZoneId.of(zone)).toLocalDate()
            admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to today.toString(), "duration_seconds" to 3600)).expect(201)
            // An hour on the last day of the month before, which a monthly budget must not count.
            val before = LocalDate.parse(month).minusDays(1)
            admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to before.toString(), "duration_seconds" to 7200)).expect(201)

            val budget = admin.get("/api/v1/projects/$project/budget").expect(200)
            assertThat(budget["period_start"].asText()).describedAs(zone).isEqualTo(month)
            assertThat(budget["spent_seconds"].asLong()).describedAs(zone).isEqualTo(3600L)
        }
    }
}
