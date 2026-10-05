// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import com.honestrobin.time.accounting.OAuthTokens
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.platform.crypto.SecretBox
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockAccounting
import com.honestrobin.time.support.MockStorecove
import com.honestrobin.time.support.MockStripe
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Move out promises "a list of everything still connected ... and how to end each one" (the
 * Robin's Code, "You can always leave"), in every state of an account. Ending access only removes
 * access, so it works while the account is read-only; connecting or changing a connection doesn't.
 */
class EndConnectionsTest : IntegrationTest() {
    @Autowired lateinit var secrets: SecretBox

    private class Connected(
        val admin: TestClient, val member: TestClient, val adminToken: UUID, val memberToken: UUID, val device: UUID, val deviceToken: String,
        val invited: UUID, val inviteToken: String, val sync: UUID, val unfinished: UUID,
        val stripeAccount: String, val qboRefresh: String, val storecoveEntity: String,
    )

    /** An account with every kind of connection that can be ended. */
    private fun connectEverything(accountName: String): Connected {
        val admin = signup(accountName = accountName, email = uniqueEmail("owner"))
        membershipId(admin)
        val member = invite(admin)
        val adminToken = admin.post("/api/v1/me/api_tokens", mapOf("name" to "Reports script", "scopes" to listOf("read"))).expect(201)["api_token"]["id"].asText()
        val memberToken = member.post("/api/v1/me/api_tokens", mapOf("name" to "Mo's timer")).expect(201)["api_token"]["id"].asText()
        val deviceClient = client().apply { sendCsrf = false }
        val start = deviceClient.post("/api/v1/auth/device", mapOf("client_name" to "Browser extension (Chrome)")).expect(200)
        member.post("/api/v1/device_authorizations/${start["user_code"].asText()}/approve").expect(204)
        val deviceToken = deviceClient.post("/api/v1/auth/device/token", mapOf("device_code" to start["device_code"].asText())).expect(200)["token"].asText()
        // Moving out lists each token with its id, so it can be revoked.
        val device = UUID.fromString(admin.get("/api/v1/account/connections").expect(200).body.values().single { it["kind"].asText() == "device" }["id"].asText())
        // An invitation not yet accepted: its link still lets someone in.
        val anaEmail = uniqueEmail("ana")
        val invited = admin.post("/api/v1/people", mapOf("name" to "Ana Pending", "email" to anaEmail, "role" to "member")).expect(201).id()
        val inviteToken = mail.linkToken(anaEmail)
        val accountId = admin.accountId!!
        // Outside services, connected through Honest Robin, with what connecting stores, so ending
        // each reaches the provider (the mocks record it).
        val stripeAccount = "acct_end_${UUID.randomUUID()}"
        val qboRefresh = "rt_end_${UUID.randomUUID()}"
        val storecoveEntity = "${(100_000..999_999).random()}"
        fun oauth(refresh: String, org: String) =
            secrets.encrypt(mapper.writeValueAsString(OAuthTokens("at_end_${UUID.randomUUID()}", refresh, Instant.now().plus(Duration.ofHours(1)), org, "Kauri Books")))
        tx.system {
            for ((kind, entity, credentials) in listOf(
                Triple("stripe", stripeAccount, null),
                Triple("qbo", "realm_${UUID.randomUUID()}", oauth(qboRefresh, "realm-end")),
                Triple("xero", MockAccounting.XERO_TENANT, oauth("rt_end_${UUID.randomUUID()}", MockAccounting.XERO_TENANT)),
                Triple("storecove", storecoveEntity, null),
            )) {
                dsl.insertInto(INTEGRATIONS).set(INTEGRATIONS.ACCOUNT_ID, accountId).set(INTEGRATIONS.KIND, kind).set(INTEGRATIONS.MODE, "connect")
                    .set(INTEGRATIONS.STATUS, "connected").set(INTEGRATIONS.EXTERNAL_ACCOUNT_ID, entity).set(INTEGRATIONS.DISPLAY_NAME, "Kauri $kind")
                    .set(INTEGRATIONS.CREDENTIALS_ENCRYPTED, credentials)
                    .apply { if (kind == "storecove") set(INTEGRATIONS.SETTINGS, JSONB.valueOf("""{"peppol_scheme":"DE:VAT","peppol_id":"DE123456789"}""")) }
                    .execute()
            }
        }
        val sync = tx.system {
            dsl.insertInto(IMPORT_JOBS).set(IMPORT_JOBS.ACCOUNT_ID, accountId).set(IMPORT_JOBS.MODE, "api").set(IMPORT_JOBS.STATUS, "syncing")
                .set(IMPORT_JOBS.EXTERNAL_ACCOUNT_ID, "1234567").set(IMPORT_JOBS.TOKEN_ENCRYPTED, "not-a-real-token")
                .set(IMPORT_JOBS.SYNC_UNTIL, Instant.now().plus(Duration.ofDays(3))).returning(IMPORT_JOBS.ID).fetchOne()!!.id
        }
        val unfinished = tx.system {
            dsl.insertInto(IMPORT_JOBS).set(IMPORT_JOBS.ACCOUNT_ID, accountId).set(IMPORT_JOBS.MODE, "api").set(IMPORT_JOBS.STATUS, "failed")
                .set(IMPORT_JOBS.EXTERNAL_ACCOUNT_ID, "7654321").set(IMPORT_JOBS.TOKEN_ENCRYPTED, "not-a-real-token").returning(IMPORT_JOBS.ID).fetchOne()!!.id
        }
        return Connected(
            admin, member, UUID.fromString(adminToken), UUID.fromString(memberToken), device, deviceToken, invited, inviteToken, sync, unfinished,
            stripeAccount, qboRefresh, storecoveEntity,
        )
    }

    private fun kinds(admin: TestClient) = admin.get("/api/v1/account/connections").expect(200).body.values().map { it["kind"].asText() }

    @Test
    fun `a read-only account can still end every connection`() {
        for (state in listOf("lapsed", "waiting to be deleted")) {
            val c = connectEverything("Kauri Lane")
            val admin = c.admin
            val accountId = admin.accountId!!
            try {
                when (state) {
                    "lapsed" -> tx.system { dsl.update(ACCOUNTS).set(ACCOUNTS.STATUS, "lapsed").set(ACCOUNTS.LAPSED_AT, Instant.now()).where(ACCOUNTS.ID.eq(accountId)).execute() }
                    "waiting to be deleted" -> admin.post("/api/v1/account/deletion", mapOf("confirm_name" to "Kauri Lane")).expect(204)
                }
                assertThat(admin.get("/api/v1/account").expect(200)["status"].asText()).describedAs(state).isNotEqualTo("active")
                assertThat(kinds(admin)).describedAs(state).contains("api_token", "device", "invitation", "stripe", "qbo", "xero", "storecove", "harvest_sync", "harvest_import")

                // The Import page lists every import that still connects, however many came after it.
                tx.system {
                    repeat(20) {
                        dsl.insertInto(IMPORT_JOBS).set(IMPORT_JOBS.ACCOUNT_ID, accountId).set(IMPORT_JOBS.MODE, "csv").set(IMPORT_JOBS.STATUS, "completed")
                            .set(IMPORT_JOBS.CREATED_AT, Instant.now().plus(Duration.ofHours(1))).execute()
                    }
                }
                val connected = admin.get("/api/v1/imports").expect(200).body.values().filter { it["connected"].asBoolean() }.map { it["id"].asText() }
                assertThat(connected).describedAs(state).containsExactlyInAnyOrder(c.sync.toString(), c.unfinished.toString())

                // Read-only still means read-only: nothing new is connected or changed.
                admin.post("/api/v1/payments/stripe/key", mapOf("secret_key" to "sk_test_x", "webhook_secret" to "whsec_x")).expectError(402, "account_read_only")
                admin.post("/api/v1/accounting/qbo/connect").expectError(402, "account_read_only")
                admin.post("/api/v1/imports/${c.unfinished}/resume").expectError(402, "account_read_only")
                // Only admins end someone else's token or an invitation, and only in their own account.
                c.member.delete("/api/v1/account/api_tokens/${c.adminToken}").expectError(403, "forbidden")
                c.member.delete("/api/v1/people/${c.invited}/invite").expectError(403, "forbidden")
                val other = signup(accountName = "Somewhere Else")
                other.delete("/api/v1/account/api_tokens/${c.memberToken}").expectError(404, "not_found")
                other.delete("/api/v1/people/${c.invited}/invite").expectError(404, "not_found")
                val xeroRemoved = MockAccounting.removed.count { it == "xero:conn-1" }

                // Each one ends, in the way the Move out page says.
                admin.delete("/api/v1/me/api_tokens/${c.adminToken}").expect(204)
                admin.delete("/api/v1/account/api_tokens/${c.memberToken}").expect(204)
                admin.delete("/api/v1/account/api_tokens/${c.device}").expect(204)
                assertThat(admin.delete("/api/v1/people/${c.invited}/invite").expect(200)["status"].asText()).isEqualTo("pending_invite")
                assertThat(admin.delete("/api/v1/payments/stripe").expect(200)["revoked"].asBoolean()).isTrue()
                assertThat(admin.delete("/api/v1/accounting/qbo").expect(200)["revoked"].asBoolean()).isTrue()
                assertThat(admin.delete("/api/v1/accounting/xero").expect(200)["revoked"].asBoolean()).isTrue()
                assertThat(admin.delete("/api/v1/einvoicing/peppol").expect(200)["revoked"].asBoolean()).isTrue()
                assertThat(admin.post("/api/v1/imports/${c.sync}/stop_sync").expect(200)["status"].asText()).isEqualTo("completed")
                assertThat(admin.post("/api/v1/imports/${c.unfinished}/cancel").expect(200)["status"].asText()).isEqualTo("cancelled")

                // Each provider heard that our access ends.
                assertThat(MockStripe.calls).describedAs(state).anyMatch { it.path == "/oauth/deauthorize" && it.form["stripe_user_id"] == c.stripeAccount }
                assertThat(MockAccounting.removed).describedAs(state).contains("qbo:${c.qboRefresh}")
                assertThat(MockAccounting.removed.count { it == "xero:conn-1" }).describedAs(state).isEqualTo(xeroRemoved + 1)
                assertThat(MockStorecove.calls).describedAs(state).anyMatch { it.method == "DELETE" && it.path.endsWith("/legal_entities/${c.storecoveEntity}") }
                // What was revoked or withdrawn no longer lets anyone in.
                client().apply { bearer = c.deviceToken }.get("/api/v1/me").expectError(401, "invalid_token")
                client().post("/api/v1/auth/invite/accept", mapOf("token" to c.inviteToken, "password" to "correct horse battery")).expectError(400, "invalid_link")
                // The audit log says who ended it.
                val revoked = tx.system {
                    dsl.selectFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.eq(c.memberToken)).and(AUDIT_LOG.ACTION.eq("api_tokens.delete")).fetchOne()
                }
                assertThat(revoked?.actorUserId).describedAs(state).isEqualTo(admin.userId)
                // Nothing is left connected, no Harvest token is kept, and the account is still read-only.
                assertThat(kinds(admin)).describedAs(state).isEmpty()
                assertThat(tx.system { dsl.fetchCount(IMPORT_JOBS, IMPORT_JOBS.ACCOUNT_ID.eq(accountId).and(IMPORT_JOBS.TOKEN_ENCRYPTED.isNotNull)) })
                    .describedAs(state).isZero()
                assertThat(admin.get("/api/v1/account").expect(200)["status"].asText()).describedAs(state).isNotEqualTo("active")
            } finally {
                // An account left waiting would be deleted by the next test that moves the clock past the grace period.
                if (state == "waiting to be deleted") admin.delete("/api/v1/account/deletion")
                tx.system {
                    dsl.deleteFrom(IMPORT_JOBS).where(IMPORT_JOBS.ACCOUNT_ID.eq(accountId)).execute()
                    dsl.deleteFrom(INTEGRATIONS).where(INTEGRATIONS.ACCOUNT_ID.eq(accountId)).execute()
                }
            }
        }
    }
}
