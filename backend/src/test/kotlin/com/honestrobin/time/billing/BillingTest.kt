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
        // The whole id: UUIDv7s made within a minute or so share their first characters.
        subscription: String = "sub_test_$account",
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

    /** The same request, with an admin's yes to the price of a new paid seat. */
    private fun confirmed(c: TestClient, method: HttpMethod, path: String, body: Any? = null) =
        c.request(method, path, body, params = mapOf("confirm_new_seat" to true))

    private fun seatChanges(account: UUID) = MockPaddle.calls.filter { it.method == "PATCH" && it.path == "/subscriptions/sub_test_$account" }

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

        // Now the invitation goes out, once the admin has said yes to the price; once accepted, Paddle
        // is told about the second seat, on the same price.
        admin.post("/api/v1/people/$aroha/invite").expectError(409, "seat_confirmation_required")
        MockPaddle.calls.clear()
        confirmed(admin, HttpMethod.POST, "/api/v1/people/$aroha/invite").expect(200)
        assertThat(seatChanges(account)).isEmpty()
        val member = TestClient(mockMvc, mapper)
        val email = admin.get("/api/v1/people/$aroha")["email"].asText()
        member.post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(email), "password" to "correct horse battery")).expect(200)
        val patch = seatChanges(account).single()
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
    fun `the free plan's refusal shows the Team price`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        withFreePlanOfOne {
            val admin = signup(accountName = "Kauri Ltd")
            val refused = admin.post("/api/v1/people", mapOf("name" to "Aroha", "email" to uniqueEmail("aroha"), "role" to "member"))
                .expectError(402, "subscription_required")
            // The same list prices as the billing page, per person per month.
            val details = refused["details"]
            assertThat(details["free_plan_seats"].asInt()).isEqualTo(1)
            assertThat(details["prices"].values().map { it["interval"].asText() to it["per_seat_per_month_minor"].asLong() })
                .containsExactly("year" to 700L, "month" to 850L)
            assertThat(details["prices"].values().map { it["currency"].asText() }).containsOnly("EUR")
            assertThat(refused["message"].asText()).contains("EUR 7.00", "EUR 8.50")
            // Nothing that was already there stops working.
            admin.post("/api/v1/clients", mapOf("name" to "Still here", "currency" to "EUR")).expect(201)
        }
    }

    @Test
    fun `nobody is billed for an invitation until it's accepted`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        // Checkout used to count open invitations, so a team paid for people who hadn't joined yet.
        withFreePlanOf(2) {
            val admin = signup(accountName = "Harakeke Ltd")
            val account = admin.accountId!!
            admin.post("/api/v1/people", mapOf("name" to "Wiremu", "email" to uniqueEmail("wiremu"), "role" to "member")).expect(201)

            // The billing page and checkout count the one person who can sign in; the invitation is shown on its own.
            val free = admin.get("/api/v1/billing/subscription").expect(200)
            assertThat(free["seats_used"].asInt()).isEqualTo(1)
            assertThat(free["invitations_pending"].asInt()).isEqualTo(1)
            assertThat(admin.post("/api/v1/billing/checkout", mapOf("interval" to "year")).expect(200)["quantity"].asInt()).isEqualTo(1)

            // Paddle bills one seat, and the seat sync leaves it at one while the invitation waits.
            assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
            MockPaddle.calls.clear()
            billing().syncSeats()
            assertThat(seatChanges(account)).isEmpty()
            val team = admin.get("/api/v1/billing/subscription").expect(200)
            assertThat(team["seats_billed"].asInt()).isEqualTo(1)
            assertThat(team["invitations_pending"].asInt()).isEqualTo(1)
        }
    }

    @Test
    fun `billing starts when the invitation is accepted`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Kawakawa Co")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val email = uniqueEmail("mere")
        MockPaddle.calls.clear()
        confirmed(admin, HttpMethod.POST, "/api/v1/people", mapOf("name" to "Mere", "email" to email, "role" to "member")).expect(201)

        // Sending the invitation charges nothing, now or at the next seat sync.
        billing().syncSeats()
        assertThat(seatChanges(account)).isEmpty()

        // Accepting it adds the seat at Paddle at once, prorated from that moment, on the subscription's own price.
        TestClient(mockMvc, mapper).post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(email), "password" to "correct horse battery")).expect(200)
        val patch = seatChanges(account).single()
        assertThat(patch.body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
        assertThat(patch.body!!["items"][0]["price_id"].asText()).isEqualTo(MockPaddle.ANNUAL_PRICE)
        assertThat(patch.body!!["proration_billing_mode"].asText()).isEqualTo("prorated_immediately")
        assertThat(admin.get("/api/v1/billing/subscription").expect(200)["seats_billed"].asInt()).isEqualTo(2)
    }

    @Test
    fun `inviting onto the Team plan is refused, with the price, until an admin confirms`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Manuka Works")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val person: Map<String, Any> = mapOf("name" to "Tui", "email" to uniqueEmail("tui"), "role" to "member")

        val refused = admin.post("/api/v1/people", person).expectError(409, "seat_confirmation_required")
        val details = refused["details"]
        assertThat(details["unit_price_minor"].asLong()).isEqualTo(700)
        assertThat(details["currency"].asText()).isEqualTo("EUR")
        assertThat(details["interval"].asText()).isEqualTo("year")
        assertThat(details["billing_starts"].asText()).isEqualTo("when_accepted")
        assertThat(details["seats_billed"].asInt()).isEqualTo(1)
        assertThat(refused["message"].asText()).contains("EUR 7.00 a year", "confirm_new_seat=true")
        // Nothing was saved or sent.
        assertThat(admin.get("/api/v1/people").expect(200)["data"].size()).isEqualTo(1)

        // Every way of inviting asks the same: by email, and as a link to pass on.
        val pending = admin.post("/api/v1/people", person + ("send_invite" to false)).expect(201).id()
        admin.post("/api/v1/people/$pending/invite").expectError(409, "seat_confirmation_required")
        admin.post("/api/v1/people/$pending/invite_link").expectError(409, "seat_confirmation_required")
        assertThat(admin.get("/api/v1/people/$pending")["status"].asText()).isEqualTo("pending_invite")

        // Confirmed, the invitation goes out; sending it again doesn't ask again.
        confirmed(admin, HttpMethod.POST, "/api/v1/people/$pending/invite").expect(200)
        assertThat(admin.get("/api/v1/people/$pending")["status"].asText()).isEqualTo("invited")
        admin.post("/api/v1/people/$pending/invite").expect(200)
    }

    @Test
    fun `bringing someone back onto the Team plan asks first, and bills from that moment`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Ponga Studio")
        val account = admin.accountId!!
        val member = invite(admin)
        assertThat(deliver(subscriptionEvent(account, quantity = 2))).isEqualTo(200)
        admin.patch("/api/v1/people/${member.membershipId}", mapOf("is_active" to false)).expect(200)
        billing().syncSeats()
        assertThat(admin.get("/api/v1/billing/subscription").expect(200)["seats_billed"].asInt()).isEqualTo(1)

        val refused = admin.patch("/api/v1/people/${member.membershipId}", mapOf("is_active" to true)).expectError(409, "seat_confirmation_required")
        assertThat(refused["details"]["billing_starts"].asText()).isEqualTo("now")
        assertThat(admin.get("/api/v1/people/${member.membershipId}")["is_active"].asBoolean()).isFalse()

        MockPaddle.calls.clear()
        confirmed(admin, HttpMethod.PATCH, "/api/v1/people/${member.membershipId}", mapOf("is_active" to true)).expect(200)
        assertThat(seatChanges(account).single().body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
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
    fun `deleting an account cancels its subscription at Paddle`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Matai Ltd")
        val subscription = "sub_deleted_${UUID.randomUUID().toString().take(8)}"
        assertThat(deliver(subscriptionEvent(admin.accountId!!, subscription = subscription))).isEqualTo(200)
        MockPaddle.calls.clear()
        context.getBean(com.honestrobin.time.export.AccountDeletionService::class.java).purge(admin.accountId!!)
        assertThat(MockPaddle.calls.single { it.path == "/subscriptions/$subscription/cancel" }.body!!["effective_from"].asText()).isEqualTo("immediately")
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
