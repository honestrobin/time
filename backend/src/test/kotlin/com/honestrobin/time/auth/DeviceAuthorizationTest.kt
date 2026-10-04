// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.db.Tables.DEVICE_AUTHORIZATIONS
import com.honestrobin.time.db.Tables.USER_SESSIONS
import com.honestrobin.time.platform.live.LiveEvents
import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Duration
import java.time.Instant

/** Spec §9: signing in the browser extension, and live updates of the timer (AT-4.1). */
class DeviceAuthorizationTest : IntegrationTest() {
    @Autowired lateinit var live: LiveEvents

    @Test
    fun `a device signs in with a code the person approves in the web app`() {
        val admin = signup(accountName = "Pounamu Labs")
        // The extension sends no cookies and no CSRF header.
        val device = client().apply { sendCsrf = false }
        val start = device.post("/api/v1/auth/device", mapOf("client_name" to "Browser extension (Chrome)")).expect(200)
        val deviceCode = start["device_code"].asText()
        val userCode = start["user_code"].asText()
        assertThat(userCode).matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}")
        assertThat(start["verification_uri_complete"].asText()).endsWith("/device?code=$userCode")
        assertThat(start["interval"].asInt()).isEqualTo(2)

        device.post("/api/v1/auth/device/token", mapOf("device_code" to deviceCode)).expectError(400, "authorization_pending")
        // The approval page shows which device asks; codes are forgiving about case and dashes.
        assertThat(admin.get("/api/v1/device_authorizations/${userCode.lowercase().replace("-", "")}").expect(200)["client_name"].asText())
            .isEqualTo("Browser extension (Chrome)")
        // Approving creates a token, so it needs a recent sign-in.
        tx.system { dsl.update(USER_SESSIONS).set(USER_SESSIONS.AUTHENTICATED_AT, Instant.now().minus(Duration.ofHours(1))).where(USER_SESSIONS.USER_ID.eq(admin.userId)).execute() }
        admin.post("/api/v1/device_authorizations/$userCode/approve").expectError(403, "reauth_required")
        admin.post("/api/v1/auth/reauth", mapOf("password" to "correct horse battery")).expect(204)
        admin.post("/api/v1/device_authorizations/$userCode/approve").expect(204)

        val collected = device.post("/api/v1/auth/device/token", mapOf("device_code" to deviceCode)).expect(200)
        assertThat(collected["account_id"].asText()).isEqualTo(admin.accountId.toString())
        assertThat(collected["account_name"].asText()).isEqualTo("Pounamu Labs")
        // Collected once.
        device.post("/api/v1/auth/device/token", mapOf("device_code" to deviceCode)).expectError(400, "invalid_grant")

        // The token acts as the person in that account, with write access.
        val ext = client().apply { bearer = collected["token"].asText() }
        assertThat(ext.get("/api/v1/me").expect(200)["email"].asText()).isEqualTo(admin.email)
        val tokens = admin.get("/api/v1/me/api_tokens").expect(200).body
        assertThat(tokens.values().map { it["name"].asText() }).contains("Browser extension (Chrome)")
        // Security review, 4 October 2026: a device token expires when unused for 90 days, and the
        // person hears about every device connected, so a phished code doesn't go unnoticed.
        val expires = Instant.parse(tokens.values().first { it["name"].asText() == "Browser extension (Chrome)" }["expires_at"].asText())
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofDays(89), Duration.ofDays(91))
        assertThat(mail.lastTo(admin.email!!).text).contains("A device was connected to your account: \"Browser extension (Chrome)\"")
    }

    @Test
    fun `an approved code must be collected soon after`() {
        val admin = signup()
        val device = client().apply { sendCsrf = false }
        val start = device.post("/api/v1/auth/device", mapOf("client_name" to "Browser extension (Chrome)")).expect(200)
        admin.post("/api/v1/device_authorizations/${start["user_code"].asText()}/approve").expect(204)
        tx.system {
            dsl.update(DEVICE_AUTHORIZATIONS).set(DEVICE_AUTHORIZATIONS.APPROVED_AT, Instant.now().minus(Duration.ofMinutes(11)))
                .where(DEVICE_AUTHORIZATIONS.USER_CODE.eq(start["user_code"].asText())).execute()
        }
        device.post("/api/v1/auth/device/token", mapOf("device_code" to start["device_code"].asText())).expectError(400, "expired_token")
    }

    @Test
    fun `a declined or unknown code gives the device nothing`() {
        val admin = signup()
        val device = client()
        val start = device.post("/api/v1/auth/device", mapOf("client_name" to "Browser extension (Firefox)")).expect(200)
        admin.post("/api/v1/device_authorizations/${start["user_code"].asText()}/deny").expect(204)
        device.post("/api/v1/auth/device/token", mapOf("device_code" to start["device_code"].asText())).expectError(400, "access_denied")
        device.post("/api/v1/auth/device/token", mapOf("device_code" to "nonsense")).expectError(400, "invalid_grant")
        admin.get("/api/v1/device_authorizations/BCDF-GHJK").expectError(404, "not_found")
    }

    @Test
    fun `one person can hold only a few live streams at once`() {
        val membership = java.util.UUID.randomUUID()
        repeat(LiveEvents.MAX_STREAMS_PER_MEMBERSHIP + 3) { live.subscribe(membership) }
        assertThat(live.openStreams(membership)).isEqualTo(LiveEvents.MAX_STREAMS_PER_MEMBERSHIP)
    }

    @Test
    fun `open streams hear about a timer started elsewhere within a moment (AT-4_1)`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        val result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/me/events")
                .cookie(*admin.cookies.values.toTypedArray())
                .header("HonestRobin-Account-Id", admin.accountId.toString()),
        ).andReturn()
        assertThat(result.request.isAsyncStarted).isTrue()
        assertThat(result.response.contentAsString).contains("event:ready")
        assertThat(live.openStreams()).isPositive()

        // Someone else's change says nothing on this stream.
        val other = signup()
        val otherTask = createTask(other)
        other.post("/api/v1/time_entries", mapOf("project_id" to createProject(other, taskIds = listOf(otherTask)).id(), "task_id" to otherTask, "spent_date" to "2026-10-03", "duration_seconds" to 3600)).expect(201)

        val started = Instant.now()
        admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to "2026-10-03", "duration_seconds" to 3600)).expect(201)
        while (!result.response.contentAsString.contains("event:time_entries") && Duration.between(started, Instant.now()) < Duration.ofSeconds(2)) {
            Thread.sleep(20)
        }
        assertThat(result.response.contentAsString).contains("event:time_entries")
        assertThat(result.response.contentAsString.split("event:time_entries").size - 1).isEqualTo(1)
    }

    @Test
    fun `the instance serves the extension's selector config, for fixes without a store release`() {
        val res = client().get("/extension/selectors.json").expect(200)
        assertThat(res["version"].asInt()).isPositive()
        assertThat(res["sites"].values().map { it["source"].asText() }).containsExactlyInAnyOrder("github", "jira", "asana", "linear", "trello")
    }
}

