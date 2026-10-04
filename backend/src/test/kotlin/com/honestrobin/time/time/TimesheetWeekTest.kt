// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimesheetWeekTest : IntegrationTest() {

    @Test
    fun `cells create, update and clear entries`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        fun cell(date: String, seconds: Int) =
            admin.put("/api/v1/timesheets/week/cell", mapOf("project_id" to project, "task_id" to task, "spent_date" to date, "duration_seconds" to seconds))

        var week = cell("2026-09-15", 3600).expect(200)
        assertThat(week["week_start"].asText()).isEqualTo("2026-09-14") // Monday
        assertThat(week["rows"]).hasSize(1)
        assertThat(week["rows"][0]["days"][1].asLong()).isEqualTo(3600)
        week = cell("2026-09-15", 5400).expect(200)
        assertThat(week["entries"]).hasSize(1)
        assertThat(week["total"].asLong()).isEqualTo(5400)
        week = cell("2026-09-15", 0).expect(200)
        assertThat(week["entries"]).isEmpty()

        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-16", "duration_seconds" to 60)).expect(201)
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-16", "duration_seconds" to 60)).expect(201)
        cell("2026-09-16", 600).expectError(409, "multiple_entries")
    }

    @Test
    fun `rows can be pinned and copied from the previous week`() {
        val admin = signup()
        val taskA = createTask(admin)
        val taskB = createTask(admin)
        val project = createProject(admin, taskIds = listOf(taskA, taskB)).id()
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to taskA, "spent_date" to "2026-09-08", "duration_seconds" to 60)).expect(201)
        admin.post("/api/v1/timesheets/week/rows", mapOf("start" to "2026-09-07", "project_id" to project, "task_id" to taskB)).expect(200)

        val copied = admin.post("/api/v1/timesheets/week/copy_previous", mapOf("start" to "2026-09-21")).expect(200)
        assertThat(copied["rows"].values().map { it["task"]["id"].asText() }).containsExactlyInAnyOrder(taskA.toString(), taskB.toString())
        assertThat(copied["total"].asLong()).isZero()

        val removed = admin.delete("/api/v1/timesheets/week/rows?start=2026-09-21&project_id=$project&task_id=$taskB").expect(200)
        assertThat(removed["rows"]).hasSize(1)
    }

    @Test
    fun `week starts follow the account setting`() {
        val admin = signup()
        admin.patch("/api/v1/account", mapOf("week_start" to 7)).expect(200)
        val week = admin.get("/api/v1/timesheets/week", mapOf("start" to "2026-09-16")).expect(200)
        assertThat(week["week_start"].asText()).isEqualTo("2026-09-13") // Sunday
    }
}
