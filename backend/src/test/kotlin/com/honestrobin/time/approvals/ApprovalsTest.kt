// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.approvals

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * AT-1.3: a submitted week is read-only to the member; manager approval locks entries;
 * an admin unlock (reopen) writes an audit entry with the reason.
 */
class ApprovalsTest : IntegrationTest() {

    @Test
    fun `submit, approve, lock and reopen`() {
        val admin = signup()
        admin.patch("/api/v1/account", mapOf("approvals_enabled" to true)).expect(200)
        val manager = invite(admin, role = "manager", name = "Mia Manager")
        val member = invite(admin, name = "Max Member")
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), members = listOf(manager, member)).id()
        val pm = admin.get("/api/v1/projects/$project").expect(200)["members"].first { it["membership_id"].asText() == manager.membershipId.toString() }
        admin.patch("/api/v1/projects/$project/members/${pm["id"].asText()}", mapOf("is_manager" to true)).expect(200)

        val monday = "2026-09-07"
        val entry = member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to monday, "duration_seconds" to 7200)).expect(201)

        val week = member.post("/api/v1/timesheets/week/submit", mapOf("start" to monday)).expect(200)
        assertThat(week["submission"]["state"].asText()).isEqualTo("submitted")
        assertThat(week["is_read_only"].asBoolean()).isTrue()

        // Read-only to the member: no edits, deletes or new entries in that week.
        member.patch("/api/v1/time_entries/${entry.id()}", mapOf("duration_seconds" to 3600)).expectError(409, "week_submitted")
        member.delete("/api/v1/time_entries/${entry.id()}").expectError(409, "week_submitted")
        member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-08", "duration_seconds" to 60))
            .expectError(409, "week_submitted")
        // A member can't approve anything.
        val submissionId = week["submission"]["id"].asText()
        member.post("/api/v1/approvals/$submissionId/approve").expectError(403, "forbidden")

        val pending = manager.get("/api/v1/approvals").expect(200)
        assertThat(pending.body.values().map { it["person"]["name"].asText() }).contains("Max Member")

        val approved = manager.post("/api/v1/approvals/$submissionId/approve").expect(200)
        assertThat(approved["submission"]["state"].asText()).isEqualTo("approved")
        val locked = admin.get("/api/v1/time_entries/${entry.id()}").expect(200)
        assertThat(locked["approval_state"].asText()).isEqualTo("approved")
        assertThat(locked["is_locked"].asBoolean()).isTrue()
        manager.patch("/api/v1/time_entries/${entry.id()}", mapOf("notes" to "x")).expectError(409, "entry_locked")
        admin.patch("/api/v1/time_entries/${entry.id()}", mapOf("notes" to "x")).expectError(409, "entry_locked")

        // Only admins reopen, and they must say why.
        manager.post("/api/v1/approvals/$submissionId/reopen", mapOf("reason" to "fix")).expectError(403, "forbidden")
        admin.post("/api/v1/approvals/$submissionId/reopen", mapOf("reason" to " ")).expectError(422, "validation_failed")
        admin.post("/api/v1/approvals/$submissionId/reopen", mapOf("reason" to "Client asked for a correction")).expect(200)

        val audit = admin.get("/api/v1/audit_log", mapOf("entity_type" to "time_entries", "entity_id" to entry.id())).expect(200)
        val unlock = audit["entries"].first { it["diff"].has("is_locked") }
        assertThat(unlock["reason"].asText()).isEqualTo("Client asked for a correction")
        assertThat(unlock["actor_user_id"].asText()).isEqualTo(admin.userId.toString())
        assertThat(admin.get("/api/v1/audit_log", mapOf("entity_type" to "timesheet_submissions")).expect(200)["entries"].values().map { it["action"].asText() })
            .contains("timesheet.reopen")

        member.patch("/api/v1/time_entries/${entry.id()}", mapOf("duration_seconds" to 3600)).expect(200)
    }

    @Test
    fun `rejected weeks become editable and the member is told why`() {
        val admin = signup()
        admin.patch("/api/v1/account", mapOf("approvals_enabled" to true)).expect(200)
        val member = invite(admin)
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), members = listOf(member)).id()
        val entry = member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-09-14", "duration_seconds" to 600)).expect(201)
        val week = member.post("/api/v1/timesheets/week/submit", mapOf("start" to "2026-09-14")).expect(200)
        admin.post("/api/v1/approvals/${week["submission"]["id"].asText()}/reject", mapOf("comment" to "Add notes please")).expect(200)
        assertThat(mail.lastTo(member.email!!).text).contains("Add notes please")
        member.patch("/api/v1/time_entries/${entry.id()}", mapOf("notes" to "Wireframes")).expect(200)
        member.post("/api/v1/timesheets/week/submit", mapOf("start" to "2026-09-14")).expect(200)
    }
}
