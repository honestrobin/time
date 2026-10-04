// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class TimerTest : IntegrationTest() {
    /**
     * Runs [block] at midday in the accounts' time zone. Other tests move the shared clock by hours,
     * and a timer that crosses midnight between two steps is (rightly) a different day's entry.
     */
    private fun atMidday(block: () -> Unit) {
        val zone = java.time.ZoneId.of("Europe/Zagreb")
        val previous = clock.travelTo(java.time.LocalDate.now(clock.withZone(zone)).atTime(12, 0).atZone(zone).toInstant())
        try {
            block()
        } finally {
            clock.restore(previous)
        }
    }


    /** AT-1.1: starting a second timer stops the first; both entries have correct durations. */
    @Test
    fun `starting a second timer stops the first with correct durations`() = atMidday {
        val admin = signup()
        val taskA = createTask(admin)
        val taskB = createTask(admin)
        val project = createProject(admin, taskIds = listOf(taskA, taskB)).id()

        val first = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to taskA, "notes" to "Design")).expect(201)
        assertThat(first["is_running"].asBoolean()).isTrue()
        clock.advance(Duration.ofMinutes(30))

        val second = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to taskB)).expect(201)
        assertThat(second["is_running"].asBoolean()).isTrue()

        val stoppedFirst = admin.get("/api/v1/time_entries/${first.id()}").expect(200)
        assertThat(stoppedFirst["is_running"].asBoolean()).isFalse()
        assertThat(stoppedFirst["duration_seconds"].asLong()).isBetween(1800L, 1802L)

        clock.advance(Duration.ofMinutes(15))
        val stoppedSecond = admin.post("/api/v1/time_entries/${second.id()}/stop").expect(200)
        assertThat(stoppedSecond["is_running"].asBoolean()).isFalse()
        assertThat(stoppedSecond["duration_seconds"].asLong()).isBetween(900L, 902L)

        admin.get("/api/v1/me/timer").expect(204)
    }

    @Test
    fun `restarting an entry accumulates time and running entries report elapsed time`() = atMidday {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        val e = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task)).expect(201)
        clock.advance(Duration.ofMinutes(10))
        assertThat(admin.get("/api/v1/me/timer").expect(200)["duration_seconds"].asLong()).isBetween(600L, 602L)
        admin.post("/api/v1/time_entries/${e.id()}/stop").expect(200)

        admin.post("/api/v1/time_entries/${e.id()}/start").expect(200)
        clock.advance(Duration.ofMinutes(5))
        val stopped = admin.post("/api/v1/time_entries/${e.id()}/stop").expect(200)
        assertThat(stopped["duration_seconds"].asLong()).isBetween(900L, 904L)
    }

    @Test
    fun `timers are capped at 24 hours and only run today`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2020-01-01"))
            .expectError(422, "validation_failed")

        val e = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task)).expect(201)
        val date = e["spent_date"].asText()
        clock.advance(Duration.ofHours(30))
        val stopped = admin.post("/api/v1/time_entries/${e.id()}/stop").expect(200)
        assertThat(stopped["duration_seconds"].asLong()).isEqualTo(86_400L)
        // A timer that crosses midnight keeps the day it started on.
        assertThat(stopped["spent_date"].asText()).isEqualTo(date)
    }

    @Test
    fun `durations can be entered directly or as start and end times`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        val byDuration = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-01", "duration_seconds" to 5400)).expect(201)
        assertThat(byDuration["is_running"].asBoolean()).isFalse()
        assertThat(byDuration["duration_seconds"].asLong()).isEqualTo(5400)

        val byTimes = admin.post(
            "/api/v1/time_entries",
            mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-01", "start_time" to "09:15", "end_time" to "11:45"),
        ).expect(201)
        assertThat(byTimes["duration_seconds"].asLong()).isEqualTo(9000)

        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-01", "duration_seconds" to 90_000))
            .expectError(422, "validation_failed")
    }

    @Test
    fun `people can only track time on projects and tasks they are assigned to`() {
        val admin = signup()
        val member = invite(admin)
        val task = createTask(admin)
        val otherTask = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "duration_seconds" to 60))
            .expectError(422, "validation_failed")

        val assigned = createProject(admin, taskIds = listOf(task), members = listOf(member)).id()
        member.post("/api/v1/time_entries", mapOf("project_id" to assigned, "task_id" to otherTask, "duration_seconds" to 60))
            .expectError(422, "validation_failed")
        member.post("/api/v1/time_entries", mapOf("project_id" to assigned, "task_id" to task, "duration_seconds" to 60)).expect(201)
        assertThat(member.get("/api/v1/me/assignments").expect(200).body.values().map { it["project_id"].asText() }).containsExactly(assigned.toString())
    }

    @Test
    fun `stale If-Match is rejected`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        val e = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "duration_seconds" to 60)).expect(201)
        val etag = e.headers["ETag"]!!.first()
        admin.patch("/api/v1/time_entries/${e.id()}", mapOf("notes" to "first")).expect(200)
        val stale = admin.request(org.springframework.http.HttpMethod.PATCH, "/api/v1/time_entries/${e.id()}", mapOf("notes" to "second"), headers = mapOf("If-Match" to etag))
        stale.expectError(412, "stale")
    }
}
