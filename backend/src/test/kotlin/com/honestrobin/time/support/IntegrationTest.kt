// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.honestrobin.time.platform.db.Tx
import tools.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var mail: RecordingMailTransport

    @Autowired
    lateinit var dsl: DSLContext

    @Autowired
    lateinit var tx: Tx

    @Autowired
    lateinit var clock: MutableClock

    fun client() = TestClient(mockMvc, mapper)

    fun uniqueEmail(prefix: String = "user") = "$prefix-${UUID.randomUUID().toString().take(8)}@example.test"

    /** Signs up a fresh user + account; the returned client is signed in and addressed to that account. */
    fun signup(
        name: String = "Ada Admin",
        accountName: String = "Acme Studio",
        email: String = uniqueEmail("admin"),
        timezone: String = "Europe/Zagreb",
        currency: String = "EUR",
        weekStart: Int? = null,
        verifyEmail: Boolean = true,
    ): TestClient {
        val c = client()
        val res = c.post(
            "/api/v1/auth/signup",
            mapOf(
                "name" to name, "email" to email, "password" to "correct horse battery", "account_name" to accountName,
                "timezone" to timezone, "default_currency" to currency, "week_start" to weekStart,
            ),
        ).expect(201)
        c.accountId = UUID.fromString(res.body["account_id"].asText())
        c.email = email
        c.userId = UUID.fromString(res.body["user_id"].asText())
        // Sign-up is open in tests, so the account may email others only once the address is confirmed.
        if (verifyEmail) c.post("/api/v1/auth/verify_email", mapOf("token" to mail.linkToken(email))).expect(204)
        return c
    }

    /** Invites a person into [admin]'s account and returns a client signed in as them. */
    fun invite(
        admin: TestClient,
        role: String = "member",
        name: String = "Mo Member",
        extra: Map<String, Any?> = emptyMap(),
    ): TestClient {
        val email = uniqueEmail(role)
        val person = admin.post("/api/v1/people", mapOf("name" to name, "email" to email, "role" to role) + extra).expect(201)
        val c = client()
        c.post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(email), "password" to "correct horse battery")).expect(200)
        c.accountId = admin.accountId
        c.email = email
        c.membershipId = person.id()
        return c
    }

    fun createClient(admin: TestClient, name: String = "Client ${UUID.randomUUID().toString().take(6)}", currency: String = "EUR"): UUID =
        admin.post("/api/v1/clients", mapOf("name" to name, "currency" to currency)).expect(201).id()

    fun createTask(admin: TestClient, name: String = "Task ${UUID.randomUUID().toString().take(6)}", defaultRate: Long? = null, isDefault: Boolean = false): UUID =
        admin.post("/api/v1/tasks", mapOf("name" to name, "default_rate" to defaultRate, "is_default" to isDefault)).expect(201).id()

    /** Creates a project with the given tasks and people assigned. Returns the project JSON. */
    fun createProject(
        admin: TestClient,
        clientId: UUID = createClient(admin),
        taskIds: List<UUID> = listOf(createTask(admin)),
        members: List<TestClient> = emptyList(),
        extra: Map<String, Any?> = emptyMap(),
    ): TestResponse = admin.post(
        "/api/v1/projects",
        mapOf("client_id" to clientId, "name" to "Project ${UUID.randomUUID().toString().take(6)}", "task_ids" to taskIds, "membership_ids" to members.map { it.membershipId }) + extra,
    ).expect(201)

    fun membershipId(c: TestClient): UUID = UUID.fromString(c.get("/api/v1/me").expect(200)["current_membership_id"].asText()).also { c.membershipId = it }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { TestDatabase.sharedUrl }
            registry.add("spring.datasource.username") { TestDatabase.username }
            registry.add("spring.datasource.password") { TestDatabase.password }
            registry.add("honestrobin.harvest.api-base-url") { MockHarvest.baseUrl }
            registry.add("honestrobin.passwords.breach-api-url") { MockPwned.baseUrl }
            registry.add("honestrobin.stripe.api-base-url") { MockStripe.baseUrl }
            registry.add("honestrobin.stripe.connect-base-url") { MockStripe.baseUrl }
            registry.add("honestrobin.stripe.connect-client-id") { MockStripe.CLIENT_ID }
            registry.add("honestrobin.stripe.platform-secret-key") { MockStripe.PLATFORM_KEY }
            registry.add("honestrobin.stripe.platform-webhook-secret") { MockStripe.PLATFORM_WEBHOOK_SECRET }
            // Only the cloud edition creates a PostHog client; the self-hosted one must send nothing.
            registry.add("honestrobin.posthog.api-key") { MockPostHog.API_KEY }
            registry.add("honestrobin.posthog.host") { MockPostHog.baseUrl }
            registry.add("honestrobin.paddle.api-base-url") { MockPaddle.baseUrl }
            registry.add("honestrobin.paddle.environment") { "sandbox" }
            registry.add("honestrobin.paddle.api-key") { MockPaddle.API_KEY }
            registry.add("honestrobin.paddle.client-token") { "test_client_token" }
            registry.add("honestrobin.paddle.webhook-secret") { MockPaddle.WEBHOOK_SECRET }
            registry.add("honestrobin.paddle.team-monthly-price-id") { MockPaddle.MONTHLY_PRICE }
            registry.add("honestrobin.paddle.team-annual-price-id") { MockPaddle.ANNUAL_PRICE }
            registry.add("honestrobin.storecove.api-base-url") { MockStorecove.baseUrl }
            registry.add("honestrobin.storecove.platform-api-key") { MockStorecove.PLATFORM_KEY }
            registry.add("honestrobin.storecove.platform-webhook-secret") { MockStorecove.PLATFORM_WEBHOOK_SECRET }
            for (p in listOf("qbo", "xero")) {
                registry.add("honestrobin.$p.client-id") { MockAccounting.CLIENT_ID }
                registry.add("honestrobin.$p.client-secret") { MockAccounting.CLIENT_SECRET }
            }
            registry.add("honestrobin.qbo.authorize-url") { "${MockAccounting.baseUrl}/connect/oauth2" }
            registry.add("honestrobin.qbo.token-url") { "${MockAccounting.baseUrl}/oauth2/v1/tokens/bearer" }
            registry.add("honestrobin.qbo.api-base-url") { MockAccounting.baseUrl }
            registry.add("honestrobin.qbo.revoke-url") { "${MockAccounting.baseUrl}/v2/oauth2/tokens/revoke" }
            registry.add("honestrobin.xero.authorize-url") { "${MockAccounting.baseUrl}/identity/connect/authorize" }
            registry.add("honestrobin.xero.token-url") { "${MockAccounting.baseUrl}/connect/token" }
            registry.add("honestrobin.xero.connections-url") { "${MockAccounting.baseUrl}/connections" }
            registry.add("honestrobin.xero.api-base-url") { "${MockAccounting.baseUrl}/api.xro/2.0" }
        }
    }
}
