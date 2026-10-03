// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Rates change only for unlocked, uninvoiced entries, and only when the user confirms (spec §5.1). */
class RateUpdateTest : IntegrationTest() {
    @Test
    fun `changing a rate leaves snapshots until applied`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), extra = mapOf("bill_by" to "project", "hourly_rate" to 10_000))
        val projectId = project.id()
        val entry = admin.post("/api/v1/time_entries", mapOf("project_id" to projectId, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 3600)).expect(201)

        admin.patch("/api/v1/projects/$projectId", mapOf("hourly_rate" to 12_000)).expect(200)
        assertThat(admin.get("/api/v1/time_entries/${entry.id()}").expect(200)["billable_rate"].asLong()).isEqualTo(10_000)

        assertThat(admin.get("/api/v1/rates/stale", mapOf("project_id" to projectId)).expect(200)["entries"].asInt()).isEqualTo(1)
        assertThat(admin.post("/api/v1/rates/apply", mapOf("project_id" to projectId)).expect(200)["entries"].asInt()).isEqualTo(1)
        assertThat(admin.get("/api/v1/time_entries/${entry.id()}").expect(200)["billable_rate"].asLong()).isEqualTo(12_000)
        assertThat(admin.get("/api/v1/rates/stale", mapOf("project_id" to projectId)).expect(200)["entries"].asInt()).isEqualTo(0)
    }
}
