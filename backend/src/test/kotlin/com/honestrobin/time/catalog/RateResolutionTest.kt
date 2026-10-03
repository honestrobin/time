// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

/** AT-1.2: rate resolution matches spec §5.1 (Harvest precedence) for all four bill_by modes. */
class RateResolutionTest : IntegrationTest() {

    data class Case(
        val name: String,
        val billBy: String,
        val projectRate: Long? = null,
        val taskDefaultRate: Long? = null,
        val projectTaskRate: Long? = null,
        val personDefaultRate: Long? = null,
        val assignmentRate: Long? = null,
        val useDefaultRates: Boolean = true,
        val projectBillable: Boolean = true,
        val taskBillable: Boolean = true,
        val entryBillable: Boolean = true,
        val expected: Long,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `billable rate snapshot`(c: Case) {
        val admin = signup()
        val person = invite(admin, extra = mapOf("default_billable_rate" to c.personDefaultRate, "cost_rate" to 4_000))
        val task = createTask(admin, defaultRate = c.taskDefaultRate)
        val project = createProject(
            admin,
            taskIds = emptyList(),
            members = listOf(person),
            extra = mapOf("bill_by" to c.billBy, "hourly_rate" to c.projectRate, "is_billable" to c.projectBillable),
        )
        val projectId = project.id()
        val pt = admin.post("/api/v1/projects/$projectId/tasks", mapOf("task_id" to task, "billable" to c.taskBillable) + (if (c.projectTaskRate != null) mapOf("hourly_rate" to c.projectTaskRate) else emptyMap()))
            .expect(201)
        if (c.billBy == "tasks" && c.projectTaskRate == null) {
            // A task assignment without its own rate falls back to the task's default rate.
            val ptId = pt["tasks"].first { it["task_id"].asText() == task.toString() }["id"].asText()
            admin.patch("/api/v1/projects/$projectId/tasks/$ptId", mapOf("hourly_rate" to null)).expect(200)
        }
        val pmId = pt["members"].first { it["membership_id"].asText() == person.membershipId.toString() }["id"].asText()
        admin.patch("/api/v1/projects/$projectId/members/$pmId", mapOf("use_default_rates" to c.useDefaultRates, "hourly_rate" to c.assignmentRate)).expect(200)

        val entry = track(person, projectId, task, c.entryBillable)
        val seen = admin.get("/api/v1/time_entries/${entry}").expect(200)
        assertThat(seen["billable_rate"].asLong()).describedAs(c.name).isEqualTo(c.expected)
        assertThat(seen["cost_rate"].asLong()).isEqualTo(4_000)
        assertThat(seen["billable_amount"].asLong()).isEqualTo(c.expected * 3 / 2) // 1.5 h
    }

    private fun track(person: TestClient, project: UUID, task: UUID, billable: Boolean): UUID =
        person.post(
            "/api/v1/time_entries",
            mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-15", "duration_seconds" to 5400, "billable" to billable),
        ).expect(201).id()

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Case("project: project rate", "project", projectRate = 10_000, taskDefaultRate = 7_000, personDefaultRate = 5_000, expected = 10_000),
            Case("project: no project rate means 0", "project", projectRate = null, taskDefaultRate = 7_000, expected = 0),
            Case("tasks: task-assignment rate wins", "tasks", taskDefaultRate = 7_000, projectTaskRate = 8_500, projectRate = 10_000, expected = 8_500),
            Case("tasks: falls back to task default rate", "tasks", taskDefaultRate = 7_000, projectTaskRate = null, expected = 7_000),
            Case("people: custom assignment rate", "people", personDefaultRate = 5_000, assignmentRate = 6_000, useDefaultRates = false, expected = 6_000),
            Case("people: default rates flag uses person rate", "people", personDefaultRate = 5_000, assignmentRate = 6_000, useDefaultRates = true, expected = 5_000),
            Case("people: no assignment rate uses person rate", "people", personDefaultRate = 5_000, assignmentRate = null, useDefaultRates = false, expected = 5_000),
            Case("none: never billed", "none", projectRate = 10_000, personDefaultRate = 5_000, expected = 0),
            Case("non-billable project", "project", projectRate = 10_000, projectBillable = false, expected = 0),
            Case("non-billable task assignment", "project", projectRate = 10_000, taskBillable = false, expected = 0),
            Case("non-billable entry", "project", projectRate = 10_000, entryBillable = false, expected = 0),
        )
    }
}
