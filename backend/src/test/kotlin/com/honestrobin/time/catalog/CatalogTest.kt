// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogTest : IntegrationTest() {

    @Test
    fun `clients with contacts`() {
        val admin = signup()
        val client = admin.post(
            "/api/v1/clients",
            mapOf("name" to "Globex", "currency" to "USD", "vat_id" to "de 123 456 789", "country_code" to "de"),
        ).expect(201)
        assertThat(client["vat_id"].asText()).isEqualTo("DE123456789")
        assertThat(client["country_code"].asText()).isEqualTo("DE")
        val id = client.id()
        admin.post("/api/v1/clients/$id/contacts", mapOf("name" to "Hank Scorpio", "email" to "hank@globex.test", "is_invoice_recipient" to true)).expect(201)
        assertThat(admin.get("/api/v1/clients/$id").expect(200)["contacts"][0]["name"].asText()).isEqualTo("Hank Scorpio")

        admin.post("/api/v1/clients", mapOf("name" to "globex")).expectError(422, "validation_failed")
        admin.patch("/api/v1/clients/$id", mapOf("is_active" to false)).expect(200)
        assertThat(admin.get("/api/v1/clients", mapOf("is_active" to true)).expect(200)["data"].map { it["id"].asText() }).doesNotContain(id.toString())

        createProject(admin, clientId = id)
        admin.delete("/api/v1/clients/$id").expectError(409, "client_in_use")
    }

    @Test
    fun `new projects get default tasks, the creator as manager and people with access to all future projects`() {
        val admin = signup()
        val auto = invite(admin, name = "Ava Auto", extra = mapOf("has_access_to_all_future_projects" to true))
        val design = createTask(admin, name = "Design", isDefault = true)
        createTask(admin, name = "Sales")
        val project = admin.post("/api/v1/projects", mapOf("client_id" to createClient(admin), "name" to "Website")).expect(201)
        assertThat(project["tasks"].map { it["task_id"].asText() }).containsExactly(design.toString())
        val members = project["members"].associate { it["membership_id"].asText() to it["is_manager"].asBoolean() }
        assertThat(members[membershipId(admin).toString()]).isTrue()
        assertThat(members[auto.membershipId.toString()]).isFalse()
        assertThat(auto.get("/api/v1/me/assignments").expect(200).body.map { it["project_name"].asText() }).containsExactly("Website")
    }

    @Test
    fun `members and plain managers cannot manage the catalog`() {
        val admin = signup()
        val member = invite(admin)
        val manager = invite(admin, role = "manager", extra = mapOf("can_manage_projects" to false))
        member.get("/api/v1/clients").expectError(403, "forbidden")
        member.get("/api/v1/projects").expectError(403, "forbidden")
        member.post("/api/v1/tasks", mapOf("name" to "X")).expectError(403, "forbidden")
        manager.post("/api/v1/clients", mapOf("name" to "Nope")).expectError(403, "forbidden")

        val project = createProject(admin, members = listOf(manager)).id()
        manager.get("/api/v1/projects/$project").expectError(403, "forbidden")
        val pm = admin.get("/api/v1/projects/$project").expect(200)["members"].first { it["membership_id"].asText() == manager.membershipId.toString() }
        admin.patch("/api/v1/projects/$project/members/${pm["id"].asText()}", mapOf("is_manager" to true)).expect(200)
        manager.patch("/api/v1/projects/$project", mapOf("notes" to "Kickoff on Monday")).expect(200)
        // Members cannot be made project managers.
        val memberPm = admin.post("/api/v1/projects/$project/members", mapOf("membership_id" to member.membershipId)).expect(201)["members"]
            .first { it["membership_id"].asText() == member.membershipId.toString() }
        admin.patch("/api/v1/projects/$project/members/${memberPm["id"].asText()}", mapOf("is_manager" to true)).expectError(422, "validation_failed")
    }

    @Test
    fun `projects with tracked time cannot be deleted, only archived`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "duration_seconds" to 60)).expect(201)
        admin.delete("/api/v1/projects/$project").expectError(409, "project_in_use")
        admin.patch("/api/v1/projects/$project", mapOf("is_active" to false)).expect(200)
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "duration_seconds" to 60)).expectError(422, "validation_failed")
    }
}
