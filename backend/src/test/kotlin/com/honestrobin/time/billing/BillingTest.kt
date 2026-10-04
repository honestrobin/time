// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.billing

import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.edition.Edition
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockPaddle
import com.honestrobin.time.support.MockPostHog
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import java.time.Instant
import java.util.UUID

/** Spec §14 and AT-6.2, against MockPaddle. */
class BillingTest : IntegrationTest() {
    @Autowired lateinit var props: HonestRobinProperties

    @Autowired lateinit var context: ApplicationContext

    private fun billing() = context.getBean(BillingService::class.java)

    private fun <T> withFreePlanOfOne(block: () -> T): T = withFreePlanOf(1, block)

    private fun <T> withFreePlanOf(seats: Int, block: () -> T): T {
        val plan = context.getBean(FreePlan::class.java)
        plan.seats = seats
        try {
            return block()
        } finally {
            plan.seats = 1000
        }
    }

    private fun subscriptionEvent(
        account: UUID,
        type: String = "subscription.created",
        status: String = "active",
        quantity: Int = 1,
        unitPrice: Long = 700,
        priceId: String = MockPaddle.ANNUAL_PRICE,
        subscription: String = "sub_test_${account.toString().take(8)}",
        signature: String? = null,
        occurredAt: Instant = Instant.now(),
    ) = """{"event_id":"evt_${UUID.randomUUID()}","event_type":"$type","occurred_at":"$occurredAt",
        "data":{"id":"$subscription","status":"$status","customer_id":"ctm_${account.toString().take(8)}","currency_code":"EUR",
        "billing_cycle":{"interval":"year","frequency":1},
        "current_billing_period":{"starts_at":"2026-10-01T00:00:00Z","ends_at":"2027-10-01T00:00:00Z"},
        "items":[{"quantity":$quantity,"price":{"id":"$priceId","unit_price":{"amount":"$unitPrice","currency_code":"EUR"}}}],
        "custom_data":{"account_id":"$account","signature":"${signature ?: PaddleClient.sign(MockPaddle.WEBHOOK_SECRET, 0, "account:$account")}"}}}"""

    private fun deliver(payload: String, secret: String = MockPaddle.WEBHOOK_SECRET): Int {
        val ts = Instant.now().epochSecond
        return client().request(HttpMethod.POST, "/webhooks/paddle", payload, headers = mapOf("Paddle-Signature" to "ts=$ts;h1=${PaddleClient.sign(secret, ts, payload)}")).status
    }

    private fun locked(account: UUID) = tx.system { dsl.select(SUBSCRIPTIONS.LOCKED_UNIT_PRICE_MINOR).from(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(account)).fetchOne()!!.value1() }

    @Test
    fun `free for one person, a Team subscription for more, seats kept in step and the price never raised`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        withFreePlanOfOne { subscriptionJourney() }
    }

    private fun subscriptionJourney() {
        val admin = signup(accountName = "Pohutukawa Studio")
        val account = admin.accountId!!
        val free = admin.get("/api/v1/billing/subscription").expect(200)
        assertThat(free["plan"].asText()).isEqualTo("free")
        assertThat(free["seats_used"].asInt()).isEqualTo(1)
        assertThat(free["billing_available"].asBoolean()).isTrue()
        assertThat(free["prices"].values().map { it["per_seat_per_month_minor"].asLong() }).containsExactly(850, 700)

        // A second person needs a subscription; adding them without an invitation is fine.
        admin.post("/api/v1/people", mapOf("name" to "Aroha", "email" to uniqueEmail("aroha"), "role" to "member")).expectError(402, "subscription_required")
        val aroha = admin.post("/api/v1/people", mapOf("name" to "Aroha", "email" to uniqueEmail("aroha"), "role" to "member", "send_invite" to false)).expect(201).id()

        val checkout = admin.post("/api/v1/billing/checkout", mapOf("interval" to "year")).expect(200)
        assertThat(checkout["price_id"].asText()).isEqualTo(MockPaddle.ANNUAL_PRICE)
        assertThat(checkout["environment"].asText()).isEqualTo("sandbox")
        assertThat(checkout["custom_data"]["account_id"].asText()).isEqualTo(account.toString())
        assertThat(checkout["email"].asText()).isEqualTo(admin.email)

        assertThat(checkout["custom_data"]["signature"].asText()).isNotBlank()

        // Events that name the account without our signature, or a price that isn't ours, change nothing.
        deliver(subscriptionEvent(account, signature = "forged", subscription = "sub_forged"))
        deliver(subscriptionEvent(account, priceId = "pri_cheap", subscription = "sub_cheap"))
        assertThat(admin.get("/api/v1/billing/subscription")["plan"].asText()).isEqualTo("free")

        // Paddle reports the new subscription.
        val created = subscriptionEvent(account)
        assertThat(deliver(created, secret = "wrong")).isEqualTo(400)
        assertThat(deliver(created)).isEqualTo(200)
        assertThat(deliver(created)).isEqualTo(200) // a retry changes nothing
        val team = admin.get("/api/v1/billing/subscription").expect(200)
        assertThat(team["plan"].asText()).isEqualTo("team")
        assertThat(team["locked_unit_price_minor"].asLong()).isEqualTo(700)
        assertThat(team["interval"].asText()).isEqualTo("year")
        admin.post("/api/v1/billing/checkout", mapOf("interval" to "year")).expectError(409, "already_subscribed")

        // Now the invitation goes out; once accepted, Paddle is told about the second seat, on the same price.
        admin.post("/api/v1/people/$aroha/invite").expect(200)
        val member = TestClient(mockMvc, mapper)
        val email = admin.get("/api/v1/people/$aroha")["email"].asText()
        member.post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(email), "password" to "correct horse battery")).expect(200)
        MockPaddle.calls.clear()
        assertThat(billing().syncSeats()).isGreaterThanOrEqualTo(1)
        val patch = MockPaddle.calls.single { it.method == "PATCH" && it.path == "/subscriptions/sub_test_${account.toString().take(8)}" }
        assertThat(patch.body!!["items"][0]["price_id"].asText()).isEqualTo(MockPaddle.ANNUAL_PRICE)
        assertThat(patch.body!!["items"][0]["quantity"].asInt()).isEqualTo(2)

        // The price per seat may come down but never go up.
        deliver(subscriptionEvent(account, type = "subscription.updated", quantity = 2, unitPrice = 900))
        assertThat(locked(account)).isEqualTo(700)
        deliver(subscriptionEvent(account, type = "subscription.updated", quantity = 2, unitPrice = 600))
        assertThat(locked(account)).isEqualTo(600)

        assertThat(admin.post("/api/v1/billing/portal").expect(200)["url"].asText()).startsWith("https://customer-portal.paddle.test/")

        // Cancelled with two people: read-only, export still works; subscribing again lifts it.
        deliver(subscriptionEvent(account, type = "subscription.canceled", status = "canceled", quantity = 2, unitPrice = 600))
        assertThat(admin.get("/api/v1/account")["status"].asText()).isEqualTo("lapsed")
        admin.post("/api/v1/clients", mapOf("name" to "New", "currency" to "EUR")).expectError(402, "account_read_only")
        admin.post("/api/v1/exports").expect(202)
        deliver(subscriptionEvent(account, type = "subscription.resumed", status = "active", quantity = 2, unitPrice = 600))
        assertThat(admin.get("/api/v1/account")["status"].asText()).isEqualTo("active")

        val events = (1..100).asSequence().map { Thread.sleep(50); MockPostHog.forAccount(account) }.first { "subscription_started" in it }
        assertThat(events.count { it == "subscription_started" }).isEqualTo(1)
    }

    @Test
    fun `bringing someone back with sign-in access takes a seat too`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        // Security review, 4 October 2026: reactivating a person skipped the seat check.
        withFreePlanOf(2) {
            val admin = signup(accountName = "Rimu Works")
            val member = invite(admin)
            admin.patch("/api/v1/people/${member.membershipId}", mapOf("is_active" to false)).expect(200)
            val newcomer = admin.post("/api/v1/people", mapOf("name" to "Tama", "email" to uniqueEmail("tama"), "role" to "member")).expect(201).id()
            admin.patch("/api/v1/people/${member.membershipId}", mapOf("is_active" to true)).expectError(402, "subscription_required")
            // Someone without sign-in access is free, so they can come back.
            val noAccess = admin.post("/api/v1/people", mapOf("name" to "Hemi", "email" to uniqueEmail("hemi"), "role" to "member", "send_invite" to false)).expect(201).id()
            admin.patch("/api/v1/people/$noAccess", mapOf("is_active" to false)).expect(200)
            admin.patch("/api/v1/people/$noAccess", mapOf("is_active" to true)).expect(200)
            // Once the seat is free again, the member comes back.
            admin.patch("/api/v1/people/$newcomer", mapOf("is_active" to false)).expect(200)
            admin.patch("/api/v1/people/${member.membershipId}", mapOf("is_active" to true)).expect(200)
        }
    }

    @Test
    fun `events apply in the order they happened, and a second subscription is cancelled at Paddle`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        // Security review, 4 October 2026: Paddle retries events for days and in any order, so a
        // late retry of an old event could bring a cancelled subscription back.
        val admin = signup(accountName = "Kowhai Ltd")
        val account = admin.accountId!!
        val start = Instant.now().minusSeconds(3600)
        assertThat(deliver(subscriptionEvent(account, occurredAt = start))).isEqualTo(200)
        assertThat(deliver(subscriptionEvent(account, type = "subscription.canceled", status = "canceled", occurredAt = start.plusSeconds(600)))).isEqualTo(200)
        assertThat(deliver(subscriptionEvent(account, type = "subscription.updated", status = "active", occurredAt = start.plusSeconds(300)))).isEqualTo(200)
        assertThat(admin.get("/api/v1/billing/subscription").expect(200)["status"].asText()).isEqualTo("canceled")
        // A newer event still applies.
        assertThat(deliver(subscriptionEvent(account, type = "subscription.resumed", status = "active", occurredAt = start.plusSeconds(900)))).isEqualTo(200)
        assertThat(admin.get("/api/v1/billing/subscription").expect(200)["status"].asText()).isEqualTo("active")

        // Two checkouts at once make two subscriptions: the second is cancelled so it never renews.
        val other = signup(accountName = "Totara Ltd")
        val second = "sub_second_${UUID.randomUUID().toString().take(8)}"
        assertThat(deliver(subscriptionEvent(other.accountId!!, subscription = "sub_first_${UUID.randomUUID().toString().take(8)}"))).isEqualTo(200)
        MockPaddle.calls.clear()
        assertThat(deliver(subscriptionEvent(other.accountId!!, subscription = second))).isEqualTo(200)
        assertThat(MockPaddle.calls.single { it.path == "/subscriptions/$second/cancel" }.body!!["effective_from"].asText()).isEqualTo("immediately")
        assertThat(other.get("/api/v1/billing/subscription").expect(200)["plan"].asText()).isEqualTo("team")
    }

    @Test
    fun `the database refuses to raise a subscription's price (AT-6_2)`() {
        val admin = signup()
        val account = admin.accountId!!
        tx.system {
            dsl.insertInto(SUBSCRIPTIONS).set(SUBSCRIPTIONS.ACCOUNT_ID, account).set(SUBSCRIPTIONS.EXTERNAL_ID, "sub_lock_${UUID.randomUUID()}")
                .set(SUBSCRIPTIONS.EXTERNAL_PRICE_ID, "pri_x").set(SUBSCRIPTIONS.BILLING_INTERVAL, "month").set(SUBSCRIPTIONS.STATUS, "active")
                .set(SUBSCRIPTIONS.SEATS, 3).set(SUBSCRIPTIONS.CURRENCY, "EUR").set(SUBSCRIPTIONS.LOCKED_UNIT_PRICE_MINOR, 850L).execute()
        }
        fun update(block: org.jooq.UpdateSetFirstStep<com.honestrobin.time.db.tables.records.SubscriptionsRecord>.() -> org.jooq.UpdateSetMoreStep<*>) =
            tx.system { block(dsl.update(SUBSCRIPTIONS)).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(account)).execute() }
        assertThatThrownBy { update { set(SUBSCRIPTIONS.LOCKED_UNIT_PRICE_MINOR, 851L) } }.hasMessageContaining("price_lock")
        assertThatThrownBy { update { set(SUBSCRIPTIONS.CURRENCY, "USD") } }.hasMessageContaining("price_lock")
        update { set(SUBSCRIPTIONS.LOCKED_UNIT_PRICE_MINOR, 700L) }
        update { set(SUBSCRIPTIONS.SEATS, 9) }
        assertThat(locked(account)).isEqualTo(700)
    }
}
