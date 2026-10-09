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
    fun `a task id from another account is refused, and nothing is created`() {
        val other = signup(accountName = "Elsewhere")
        val theirTask = createTask(other, name = "Theirs")

        val admin = signup()
        val mine = createProject(admin, taskIds = listOf(createTask(admin, name = "Design"))).id()
        // On a new project, and on one that exists.
        admin.post("/api/v1/time_entries/quick", mapOf("project_name" to "Mine", "client_name" to "Harbour Ltd", "task_id" to theirTask, "duration_seconds" to 600))
            .expectError(422, "validation_failed")
        admin.post("/api/v1/time_entries/quick", mapOf("project_id" to mine, "task_id" to theirTask, "duration_seconds" to 600))
            .expectError(422, "validation_failed")

        assertThat(names(admin, "/api/v1/clients")).hasSize(1)
        assertThat(names(admin, "/api/v1/projects")).hasSize(1)
        assertThat(admin.get("/api/v1/projects/$mine").expect(200)["tasks"].values().map { it["name"].asText() }).containsExactly("Design")
        assertThat(entries(admin)).isZero()
        assertThat(names(other, "/api/v1/projects")).isEmpty()
    }

    @Test
    fun `a new project gets the account's active default tasks, and an entry with no task goes to the first`() {
        val admin = signup()
        val general = createTask(admin, name = "General", isDefault = true)
        val old = createTask(admin, name = "Old default", isDefault = true)
        admin.patch("/api/v1/tasks/$old", mapOf("is_active" to false)).expect(200)
        createTask(admin, name = "Not a default")

        val entry = admin.post("/api/v1/time_entries/quick", mapOf("project_name" to "Logo", "client_name" to "Kestrel", "duration_seconds" to 600)).expect(201)
        assertThat(entry["task"]["id"].asText()).isEqualTo(general.toString())
        val onLogo = admin.get("/api/v1/projects/${entry["project"]["id"].asText()}").expect(200)["tasks"].values().map { it["name"].asText() }
        assertThat(onLogo).containsExactly("General")

        val typed = admin.post(
            "/api/v1/time_entries/quick",
            mapOf("project_name" to "Brochure", "client_name" to "Kestrel", "task_name" to "Copywriting", "duration_seconds" to 600),
        ).expect(201)
        assertThat(typed["task"]["name"].asText()).isEqualTo("Copywriting")
        val onBrochure = admin.get("/api/v1/projects/${typed["project"]["id"].asText()}").expect(200)["tasks"].values().map { it["name"].asText() }
        assertThat(onBrochure).containsExactlyInAnyOrder("General", "Copywriting")
    }

    @Test
    fun `a rate from someone who may not see rates is ignored`() {
        val admin = signup()
        val manager = invite(admin, role = "manager", extra = mapOf("can_see_rates" to false))
        val entry = manager.post(
            "/api/v1/time_entries/quick",
            mapOf("project_name" to "Logo", "client_name" to "Kestrel", "task_name" to "Design", "hourly_rate" to 9000, "duration_seconds" to 600),
        ).expect(201)
        val rate = admin.get("/api/v1/projects/${entry["project"]["id"].asText()}").expect(200)["hourly_rate"]
        assertThat(rate.isNull || rate.isMissingNode).withFailMessage { "Expected no rate, got $rate" }.isTrue()
    }

    @Test
    fun `an unticked entry isn't billable, with a new task or a new project`() {
        val admin = signup()
        val project = createProject(admin, taskIds = listOf(createTask(admin, name = "Design"))).id()
        val unticked = admin.post("/api/v1/time_entries/quick", mapOf("project_id" to project, "task_name" to "Support", "duration_seconds" to 1800, "billable" to false))
            .expect(201)
        assertThat(unticked["billable"].asBoolean()).isFalse()
        val ticked = admin.post("/api/v1/time_entries/quick", mapOf("project_id" to project, "task_name" to "Support", "duration_seconds" to 600, "billable" to true))
            .expect(201)
        assertThat(ticked["billable"].asBoolean()).isTrue()
        val fresh = admin.post(
            "/api/v1/time_entries/quick",
            mapOf("project_name" to "Logo", "client_name" to "Kestrel", "task_name" to "Design", "duration_seconds" to 600, "billable" to false),
        ).expect(201)
        assertThat(fresh["billable"].asBoolean()).isFalse()
    }

    @Test
    fun `a new client named like one in another currency is refused at the name, and nothing is kept`() {
        val admin = signup(currency = "EUR")
        createClient(admin, name = "Kestrel & Finch", currency = "GBP")
        val res = admin.post(
            "/api/v1/time_entries/quick",
            mapOf("project_name" to "Logo", "client_name" to "kestrel & finch", "task_name" to "Design", "hourly_rate" to 9000, "duration_seconds" to 600),
        ).expectError(422, "validation_failed")
        assertThat(res["fields"]["client_name"].asText()).isEqualTo("A client with this name already exists, in GBP: pick it from the list")
        assertThat(names(admin, "/api/v1/projects")).isEmpty()
        assertThat(names(admin, "/api/v1/tasks")).isEmpty()
        assertThat(entries(admin)).isZero()
    }

    @Test
    fun `a member learns nothing about task names from the answer`() {
        val admin = signup()
        val member = invite(admin, role = "member")
        createTask(admin, name = "Research")
        val onIt = createProject(admin, taskIds = listOf(createTask(admin, name = "Design")), members = listOf(member)).id()
        val notOnIt = createProject(admin, taskIds = listOf(createTask(admin, name = "Hidden"))).id()
        val quick = { project: Any, task: String -> member.post("/api/v1/time_entries/quick", mapOf("project_id" to project, "task_name" to task, "duration_seconds" to 600)) }

        // On their own project, a task the account has and a made-up one get the same answer.
        val known = quick(onIt, "Research")
        val madeUp = quick(onIt, "No such task")
        assertThat(known.status).isEqualTo(403)
        assertThat(madeUp.status).isEqualTo(known.status)
        assertThat(madeUp.body["message"]).isEqualTo(known.body["message"])

        // On a project they aren't on, a task that's on it and one that isn't get the same answer.
        val there = quick(notOnIt, "Hidden")
        val notThere = quick(notOnIt, "Design")
        assertThat(there.status).isEqualTo(422)
        assertThat(notThere.status).isEqualTo(there.status)
        assertThat(notThere.body).isEqualTo(there.body)

        assertThat(names(admin, "/api/v1/tasks")).containsExactlyInAnyOrder("Research", "Design", "Hidden")
        assertThat(entries(member)).isZero()
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
