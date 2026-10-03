// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class PeopleTest : IntegrationTest() {

    @Test
    fun `invite, accept and sign in`() {
        val admin = signup(accountName = "Initech")
        val email = uniqueEmail("peter")
        val person = admin.post("/api/v1/people", mapOf("name" to "Peter Gibbons", "email" to email, "role" to "member", "weekly_capacity_seconds" to 126000)).expect(201)
        assertThat(person["status"].asText()).isEqualTo("invited")
        assertThat(mail.lastTo(email).subject).contains("Initech")

        val c = client()
        val lookup = c.post("/api/v1/auth/invite/lookup", mapOf("token" to mail.linkToken(email))).expect(200)
        assertThat(lookup["account_name"].asText()).isEqualTo("Initech")
        assertThat(lookup["user_exists"].asBoolean()).isFalse()
        c.post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(email), "password" to "correct horse battery")).expect(200)
        val me = c.get("/api/v1/me").expect(200)
        assertThat(me["role"].asText()).isEqualTo("member")
        assertThat(admin.get("/api/v1/people/${person.id()}").expect(200)["status"].asText()).isEqualTo("active")

        // The token is single-use.
        client().post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(email))).expectError(400, "invalid_link")
    }

    @Test
    fun `an existing user joins a second account with the same login`() {
        val first = signup()
        val second = signup()
        second.post("/api/v1/people", mapOf("name" to "Shared", "email" to first.email, "role" to "manager")).expect(201)
        val c = client()
        c.post("/api/v1/auth/invite/lookup", mapOf("token" to mail.linkToken(first.email!!))).expect(200).also {
            assertThat(it["user_exists"].asBoolean()).isTrue()
        }
        c.post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(first.email!!))).expect(200)
        assertThat(first.get("/api/v1/me").expect(200)["accounts"]).hasSize(2)
    }

    @Test
    fun `the last admin cannot be demoted or deactivated`() {
        val admin = signup()
        val me = membershipId(admin)
        admin.patch("/api/v1/people/$me", mapOf("role" to "member")).expectError(409, "last_admin")
        admin.patch("/api/v1/people/$me", mapOf("is_active" to false)).expectError(409, "last_admin")
    }

    @Test
    fun `deactivating someone stops their timer and removes their access`() {
        val admin = signup()
        val member = invite(admin)
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task), members = listOf(member)).id()
        val entry = member.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task)).expect(201)
        clock.advance(Duration.ofMinutes(20))
        admin.patch("/api/v1/people/${member.membershipId}", mapOf("is_active" to false)).expect(200)
        val stopped = admin.get("/api/v1/time_entries/${entry.id()}").expect(200)
        assertThat(stopped["is_running"].asBoolean()).isFalse()
        assertThat(stopped["duration_seconds"].asLong()).isBetween(1200L, 1203L)
        member.get("/api/v1/account").expectError(403, "not_a_member")
    }

    @Test
    fun `rates are only visible and editable with rate permission`() {
        val admin = signup()
        val manager = invite(admin, role = "manager", extra = mapOf("can_see_rates" to false))
        val person = admin.post("/api/v1/people", mapOf("name" to "Rated", "email" to uniqueEmail(), "default_billable_rate" to 12_000, "send_invite" to false)).expect(201)
        assertThat(person["status"].asText()).isEqualTo("pending_invite")
        assertThat(person["default_billable_rate"].asLong()).isEqualTo(12_000)
        val project = createProject(admin, members = listOf(manager)).id()
        admin.post("/api/v1/projects/$project/members", mapOf("membership_id" to person.id())).expect(201)
        val pm = admin.get("/api/v1/projects/$project").expect(200)["members"].first { it["membership_id"].asText() == manager.membershipId.toString() }
        admin.patch("/api/v1/projects/$project/members/${pm["id"].asText()}", mapOf("is_manager" to true)).expect(200)
        val seen = manager.get("/api/v1/people/${person.id()}").expect(200)
        assertThat(seen.body.has("default_billable_rate")).isFalse()
    }
}
