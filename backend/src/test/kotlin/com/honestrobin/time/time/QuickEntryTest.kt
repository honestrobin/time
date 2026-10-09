// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** "Enter as you go": an entry with a new project, client or task is saved whole, or not at all. */
class QuickEntryTest : IntegrationTest() {
    private fun names(c: TestClient, path: String): List<String> = c.get(path).expect(200)["data"].values().map { it["name"].asText() }

    private fun entries(c: TestClient): Int = c.get("/api/v1/time_entries").expect(200)["data"].size()

    /** A day that is surely not today in any time zone: timers can't run there. */
    private fun daysAgo() = LocalDate.now(clock).minusDays(5).toString()

    @Test
    fun `a refused entry leaves no client, project or task behind`() {
        val admin = signup()
        // The client, task and project are made first; then the entry is refused: a timer on another day.
        val res = admin.post(
            "/api/v1/time_entries/quick",
            mapOf(
                "project_name" to "Harbour website", "client_name" to "Harbour Ltd", "task_name" to "Design",
                "hourly_rate" to 8000, "spent_date" to daysAgo(), "notes" to "Homepage layout",
            ),
        ).expectError(422, "validation_failed")
        assertThat(res["fields"].has("spent_date")).isTrue()

        assertThat(names(admin, "/api/v1/clients")).isEmpty()
        assertThat(names(admin, "/api/v1/projects")).isEmpty()
        assertThat(names(admin, "/api/v1/tasks")).isEmpty()
        assertThat(entries(admin)).isZero()
    }

    @Test
    fun `a refusal after the client and task were made rolls all of it back, and names the field`() {
        val admin = signup()
        val client = createClient(admin, name = "Harbour Ltd")
        admin.post("/api/v1/projects", mapOf("client_id" to client, "name" to "Website", "task_ids" to listOf(createTask(admin, name = "Design")))).expect(201)

        // A new task is made first, then the project, whose name this client already has.
        val res = admin.post(
            "/api/v1/time_entries/quick",
            mapOf("project_name" to "website", "client_name" to "harbour ltd", "task_name" to "Copywriting", "duration_seconds" to 3600),
        ).expectError(422, "validation_failed")
        assertThat(res["fields"].size()).isEqualTo(1)
        assertThat(res["fields"]["project_name"].asText()).isEqualTo("This client already has a project with that name")

        assertThat(names(admin, "/api/v1/tasks")).containsExactly("Design")
        assertThat(names(admin, "/api/v1/projects")).containsExactly("Website")
        assertThat(entries(admin)).isZero()
    }

    @Test
    fun `says every missing name at once`() {
        val admin = signup()
        val res = admin.post("/api/v1/time_entries/quick", mapOf("project_name" to " ", "hourly_rate" to -1, "duration_seconds" to 25 * 3600))
            .expectError(422, "validation_failed")
        assertThat(res["fields"]["duration_seconds"].asText()).isEqualTo("An entry can't be longer than 24 hours")
        assertThat(listOf("project_name", "client_name", "hourly_rate", "duration_seconds").filter { res["fields"].has(it) }).hasSize(4)
        assertThat(res["fields"].size()).isEqualTo(4)
    }

    @Test
    fun `sets up an empty account in one go, the first task becoming a default one`() {
        val admin = signup()
        val entry = admin.post(
            "/api/v1/time_entries/quick",
            mapOf("project_name" to " Harbour website ", "client_name" to "Harbour Ltd", "task_name" to "General", "hourly_rate" to 8000, "duration_seconds" to 9000, "notes" to "Homepage layout"),
        ).expect(201)
        assertThat(entry["duration_seconds"].asInt()).isEqualTo(9000)
        assertThat(entry["project"]["name"].asText()).isEqualTo("Harbour website")
        assertThat(entry["task"]["name"].asText()).isEqualTo("General")

        val project = admin.get("/api/v1/projects/${entry["project"]["id"].asText()}").expect(200)
        assertThat(project["client"]["name"].asText()).isEqualTo("Harbour Ltd")
        assertThat(project["hourly_rate"].asLong()).isEqualTo(8000)
        val task = admin.get("/api/v1/tasks").expect(200)["data"].values().single()
        assertThat(task["is_default"].asBoolean()).isTrue()
    }

    @Test
    fun `reuses a client and a task whose names already exist, ignoring case`() {
        val admin = signup()
        val client = createClient(admin, name = "Acme")
        val design = createTask(admin, name = "Design")
        val entry = admin.post(
            "/api/v1/time_entries/quick",
            mapOf("project_name" to "Logo", "client_name" to "acme", "task_name" to "design", "duration_seconds" to 3600),
        ).expect(201)
        assertThat(entry["task"]["id"].asText()).isEqualTo(design.toString())
        assertThat(names(admin, "/api/v1/clients")).containsExactly("Acme")
        val project = admin.get("/api/v1/projects/${entry["project"]["id"].asText()}").expect(200)
        assertThat(project["client"]["id"].asText()).isEqualTo(client.toString())
    }

    @Test
    fun `adds a typed task to an existing project, or keeps nothing if the entry is refused`() {
        val admin = signup()
        val project = createProject(admin, taskIds = listOf(createTask(admin, name = "Design"))).id()

        admin.post("/api/v1/time_entries/quick", mapOf("project_id" to project, "task_name" to "Support", "spent_date" to daysAgo()))
            .expectError(422, "validation_failed")
        assertThat(names(admin, "/api/v1/tasks")).containsExactly("Design")

        val entry = admin.post("/api/v1/time_entries/quick", mapOf("project_id" to project, "task_name" to "Support", "duration_seconds" to 1800)).expect(201)
        assertThat(entry["task"]["name"].asText()).isEqualTo("Support")
        val tasksOnProject = admin.get("/api/v1/projects/$project").expect(200)["tasks"].values().map { it["name"].asText() }
        assertThat(tasksOnProject).containsExactlyInAnyOrder("Design", "Support")

        // Typed again, the task already on the project is simply picked.
        admin.post("/api/v1/time_entries/quick", mapOf("project_id" to project, "task_name" to "support", "duration_seconds" to 600)).expect(201)
        assertThat(names(admin, "/api/v1/tasks")).containsExactlyInAnyOrder("Design", "Support")
    }

    @Test
    fun `ids from another account are refused, and nothing is created`() {
        val other = signup(accountName = "Elsewhere")
        val theirClient = createClient(other, name = "Theirs")
        val theirProject = createProject(other, clientId = theirClient).id()

        val admin = signup()
        admin.post("/api/v1/time_entries/quick", mapOf("project_name" to "Mine", "client_id" to theirClient, "task_name" to "Design", "duration_seconds" to 600))
            .expectError(422, "validation_failed")
        val onTheirs = admin.post("/api/v1/time_entries/quick", mapOf("project_id" to theirProject, "task_name" to "Design", "duration_seconds" to 600))
        assertThat(onTheirs.status).withFailMessage { "Expected a refusal but got ${onTheirs.status}: ${onTheirs.raw}" }.isIn(403, 404)

        assertThat(names(admin, "/api/v1/tasks")).isEmpty()
        assertThat(names(admin, "/api/v1/projects")).isEmpty()
        assertThat(entries(admin)).isZero()
        assertThat(names(other, "/api/v1/tasks")).hasSize(1)
        assertThat(other.get("/api/v1/projects/$theirProject").expect(200)["tasks"].size()).isEqualTo(1)
    }

    @Test
    fun `a person who can't manage projects creates nothing`() {
        val admin = signup()
        val member = invite(admin, role = "member")
        member.post("/api/v1/time_entries/quick", mapOf("project_name" to "Side project", "client_name" to "Someone", "duration_seconds" to 600))
            .expect(403)
        assertThat(names(admin, "/api/v1/clients")).isEmpty()
        assertThat(names(admin, "/api/v1/projects")).isEmpty()
    }
}
