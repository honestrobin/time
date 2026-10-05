// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.billing

import com.honestrobin.time.auth.AuthService
import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.db.Tables.USERS
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

/** Spec §14, AT-6.2 and decision record 0020, against MockPaddle. */
class BillingTest : IntegrationTest() {
    companion object {
        /** A yearly price per person: Paddle charges a yearly price's whole amount each year (EUR 84.00, EUR 7.00 a month). */
        const val YEARLY = 8400L
    }

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
        unitPrice: Long = YEARLY,
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

    /** The same request, with an admin's yes to the price of a new paid seat (by default, the price these tests subscribe at). */
    private fun confirmed(c: TestClient, method: HttpMethod, path: String, body: Any? = null, unitPrice: Long = YEARLY, interval: String = "year") =
        c.request(method, path, body, params = mapOf("confirm_unit_price_minor" to unitPrice, "confirm_currency" to "EUR", "confirm_interval" to interval))

    /** Accepts an invitation as the person invited, with the link's token. */
    private fun accept(token: String) =
        TestClient(mockMvc, mapper).post("/api/v1/auth/invite/accept", mapOf("token" to token, "password" to "correct horse battery"))

    private fun seatsBilled(admin: TestClient) = admin.get("/api/v1/billing/subscription").expect(200)["seats_billed"].asInt()

    /** Every request about this account's subscription; other tests' subscriptions may be synced meanwhile. */
    private fun paddleCalls(account: UUID) = MockPaddle.calls.filter { it.path.startsWith("/subscriptions/sub_test_$account") }

    private fun seatChanges(account: UUID) = MockPaddle.calls.filter { it.method == "PATCH" && it.path == "/subscriptions/sub_test_$account" }

    private fun accountStatus(admin: TestClient) = admin.get("/api/v1/account").expect(200)["status"].asText()

    /** Asks for a full export and waits until it's ready to download: it works in every state of an account. */
    private fun exportWorks(admin: TestClient) {
        val id = admin.post("/api/v1/exports").expect(202).id()
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val e = admin.get("/api/v1/exports").expect(200).body.first { it["id"].asText() == id.toString() }
            if (e["status"].asText() == "ready") break
            assertThat(e["status"].asText()).withFailMessage { "Export failed: $e" }.isIn("queued", "running")
            check(System.currentTimeMillis() < deadline) { "Export did not finish: $e" }
            Thread.sleep(100)
        }
        admin.get("/api/v1/exports/$id/download").expect(200)
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
        assertThat(team["locked_unit_price_minor"].asLong()).isEqualTo(YEARLY)
        assertThat(team["interval"].asText()).isEqualTo("year")
        admin.post("/api/v1/billing/checkout", mapOf("interval" to "year")).expectError(409, "already_subscribed")

        // Now the invitation goes out, once the admin has said yes to the price; once accepted, Paddle
        // is told about the second seat, on the same price.
        admin.post("/api/v1/people/$aroha/invite").expectError(409, "seat_confirmation_required")
        MockPaddle.calls.clear()
        confirmed(admin, HttpMethod.POST, "/api/v1/people/$aroha/invite").expect(200)
        assertThat(seatChanges(account)).isEmpty()
        accept(mail.linkToken(admin.get("/api/v1/people/$aroha")["email"].asText())).expect(200)
        val patch = seatChanges(account).single()
        assertThat(patch.body!!["items"][0]["price_id"].asText()).isEqualTo(MockPaddle.ANNUAL_PRICE)
        assertThat(patch.body!!["items"][0]["quantity"].asInt()).isEqualTo(2)

        // The price per seat may come down but never go up.
        deliver(subscriptionEvent(account, type = "subscription.updated", quantity = 2, unitPrice = 9000))
        assertThat(locked(account)).isEqualTo(YEARLY)
        deliver(subscriptionEvent(account, type = "subscription.updated", quantity = 2, unitPrice = 7200))
        assertThat(locked(account)).isEqualTo(7200)

        assertThat(admin.post("/api/v1/billing/portal").expect(200)["url"].asText()).startsWith("https://customer-portal.paddle.test/")

        // Cancelled with two people: read-only, export still works; subscribing again lifts it.
        deliver(subscriptionEvent(account, type = "subscription.canceled", status = "canceled", quantity = 2, unitPrice = 7200))
        assertThat(admin.get("/api/v1/account")["status"].asText()).isEqualTo("lapsed")
        admin.post("/api/v1/clients", mapOf("name" to "New", "currency" to "EUR")).expectError(402, "account_read_only")
        admin.post("/api/v1/exports").expect(202)
        deliver(subscriptionEvent(account, type = "subscription.resumed", status = "active", quantity = 2, unitPrice = 7200))
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
            assertThat(refused["message"].asText()).contains("EUR 7.00 per person per month paid yearly (EUR 84.00 a year)", "EUR 8.50 paid monthly")
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
        accept(mail.linkToken(email)).expect(200)
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
        val email = uniqueEmail("tui")
        val person: Map<String, Any> = mapOf("name" to "Tui", "email" to email, "role" to "member")

        val refused = admin.post("/api/v1/people", person).expectError(409, "seat_confirmation_required")
        val details = refused["details"]
        assertThat(details["unit_price_minor"].asLong()).isEqualTo(YEARLY)
        assertThat(details["currency"].asText()).isEqualTo("EUR")
        assertThat(details["interval"].asText()).isEqualTo("year")
        assertThat(details["billing_starts"].asText()).isEqualTo("when_accepted")
        assertThat(details["seats_billed"].asInt()).isEqualTo(1)
        assertThat(details["stale"].asBoolean()).isFalse()
        assertThat(refused["message"].asText()).contains("EUR 84.00 a year, paid yearly", "up to EUR 84.00", "confirm_unit_price_minor=8400")
        // Nobody was added, and no invitation went out.
        assertThat(admin.get("/api/v1/people").expect(200)["data"].size()).isEqualTo(1)
        assertThat(mail.to(email)).isEmpty()

        // Every way of inviting asks the same: by email, and as a link to pass on.
        val pending = admin.post("/api/v1/people", person + ("send_invite" to false)).expect(201).id()
        admin.post("/api/v1/people/$pending/invite").expectError(409, "seat_confirmation_required")
        admin.post("/api/v1/people/$pending/invite_link").expectError(409, "seat_confirmation_required")
        assertThat(admin.get("/api/v1/people/$pending")["status"].asText()).isEqualTo("pending_invite")
        assertThat(mail.to(email)).isEmpty()

        // With a yes to the price, the invitation goes out, and nothing is charged yet.
        MockPaddle.calls.clear()
        confirmed(admin, HttpMethod.POST, "/api/v1/people/$pending/invite").expect(200)
        assertThat(admin.get("/api/v1/people/$pending")["status"].asText()).isEqualTo("invited")
        assertThat(mail.to(email)).hasSize(1)
        assertThat(seatChanges(account)).isEmpty()
    }

    @Test
    fun `a yes to a price that's no longer right is refused, with the price now`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Kowhai Works")
        assertThat(deliver(subscriptionEvent(admin.accountId!!, quantity = 1))).isEqualTo(200)
        val person: Map<String, Any> = mapOf("name" to "Nikau", "email" to uniqueEmail("nikau"), "role" to "member")

        val stale = confirmed(admin, HttpMethod.POST, "/api/v1/people", person, unitPrice = 9000).expectError(409, "seat_confirmation_required")
        assertThat(stale["details"]["unit_price_minor"].asLong()).isEqualTo(YEARLY)
        assertThat(stale["details"]["stale"].asBoolean()).isTrue()
        assertThat(stale["message"].asText()).startsWith("That's not the price now.")
        // The same amount for another billing period isn't the same price either.
        confirmed(admin, HttpMethod.POST, "/api/v1/people", person, interval = "month").expectError(409, "seat_confirmation_required")
        assertThat(admin.get("/api/v1/people").expect(200)["data"].size()).isEqualTo(1)

        confirmed(admin, HttpMethod.POST, "/api/v1/people", person).expect(201)
    }

    @Test
    fun `sending an invitation again asks again, on either plan`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        withFreePlanOf(2) {
            val admin = signup(accountName = "Rata Ltd")
            val account = admin.accountId!!
            val email = uniqueEmail("ana")
            // Within a free plan of two people, the invitation goes out without a question.
            val ana = admin.post("/api/v1/people", mapOf("name" to "Ana", "email" to email, "role" to "member")).expect(201).id()

            // The free plan is one person now: sending it again is refused like a new invitation.
            context.getBean(FreePlan::class.java).seats = 1
            admin.post("/api/v1/people/$ana/invite").expectError(402, "subscription_required")
            admin.post("/api/v1/people/$ana/invite_link").expectError(402, "subscription_required")

            // On the Team plan, sending it again asks for a yes to the price, like the first time.
            assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
            admin.post("/api/v1/people/$ana/invite").expectError(409, "seat_confirmation_required")
            confirmed(admin, HttpMethod.POST, "/api/v1/people/$ana/invite").expect(200)
            assertThat(mail.to(email)).hasSize(2)
        }
    }

    @Test
    fun `an invitation link asks first too, and works once the price is confirmed`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Titoki Co")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val id = admin.post("/api/v1/people", mapOf("name" to "Hine", "email" to uniqueEmail("hine"), "role" to "member", "send_invite" to false)).expect(201).id()

        admin.post("/api/v1/people/$id/invite_link").expectError(409, "seat_confirmation_required")
        MockPaddle.calls.clear()
        val link = confirmed(admin, HttpMethod.POST, "/api/v1/people/$id/invite_link").expect(200)
        assertThat(seatChanges(account)).isEmpty()

        accept(link["url"].asText().substringAfter('#')).expect(200)
        assertThat(seatChanges(account).single().body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
    }

    @Test
    fun `bringing someone back onto the Team plan asks first, and bills from that moment`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Ponga Studio")
        val account = admin.accountId!!
        val member = invite(admin)
        assertThat(deliver(subscriptionEvent(account, quantity = 2))).isEqualTo(200)
        val path = "/api/v1/people/${member.membershipId}"

        // Back before the seat sync lowered the count: their seat is still paid for, so there's nothing to ask.
        admin.patch(path, mapOf("is_active" to false)).expect(200)
        MockPaddle.calls.clear()
        admin.patch(path, mapOf("is_active" to true)).expect(200)
        assertThat(seatChanges(account)).isEmpty()

        admin.patch(path, mapOf("is_active" to false)).expect(200)
        billing().syncSeats()
        assertThat(seatsBilled(admin)).isEqualTo(1)

        val refused = admin.patch(path, mapOf("is_active" to true)).expectError(409, "seat_confirmation_required")
        assertThat(refused["details"]["billing_starts"].asText()).isEqualTo("now")
        assertThat(refused["message"].asText()).contains("Today, Paddle charges for what's left of the current billing period, up to EUR 84.00")
        assertThat(admin.get(path)["is_active"].asBoolean()).isFalse()

        MockPaddle.calls.clear()
        confirmed(admin, HttpMethod.PATCH, path, mapOf("is_active" to true)).expect(200)
        assertThat(seatChanges(account).single().body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
    }

    @Test
    fun `bringing back someone whose invitation is still open asks first, and bills once they accept`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Horoeka Ltd")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val email = uniqueEmail("rangi")
        val rangi = confirmed(admin, HttpMethod.POST, "/api/v1/people", mapOf("name" to "Rangi", "email" to email, "role" to "member")).expect(201).id()
        admin.patch("/api/v1/people/$rangi", mapOf("is_active" to false)).expect(200)

        val refused = admin.patch("/api/v1/people/$rangi", mapOf("is_active" to true)).expectError(409, "seat_confirmation_required")
        assertThat(refused["details"]["billing_starts"].asText()).isEqualTo("when_accepted")
        MockPaddle.calls.clear()
        confirmed(admin, HttpMethod.PATCH, "/api/v1/people/$rangi", mapOf("is_active" to true)).expect(200)
        assertThat(seatChanges(account)).isEmpty()

        accept(mail.linkToken(email)).expect(200)
        assertThat(seatChanges(account).single().body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
    }

    @Test
    fun `an invitation accepted after the subscription ended charges nothing`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Mahoe Ltd")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val email = uniqueEmail("kiri")
        confirmed(admin, HttpMethod.POST, "/api/v1/people", mapOf("name" to "Kiri", "email" to email, "role" to "member")).expect(201)
        assertThat(deliver(subscriptionEvent(account, type = "subscription.canceled", status = "canceled", quantity = 1))).isEqualTo(200)

        MockPaddle.calls.clear()
        accept(mail.linkToken(email)).expect(200)
        assertThat(seatChanges(account)).isEmpty()
        // A new subscription counts them, and checkout says so before anyone pays.
        val free = admin.get("/api/v1/billing/subscription").expect(200)
        assertThat(free["plan"].asText()).isEqualTo("free")
        assertThat(free["seats_used"].asInt()).isEqualTo(2)
        assertThat(admin.post("/api/v1/billing/checkout", mapOf("interval" to "year")).expect(200)["quantity"].asInt()).isEqualTo(2)
    }

    @Test
    fun `a lapsed account can deactivate people and is active again within the free plan`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Kahikatea Ltd")
        val account = admin.accountId!!
        val aroha = invite(admin, name = "Aroha").membershipId!!
        val hemi = invite(admin, name = "Hemi").membershipId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 3))).isEqualTo(200)
        withFreePlanOfOne {
            // The Team plan ends with three people who can sign in: read-only, export still works.
            assertThat(deliver(subscriptionEvent(account, type = "subscription.canceled", status = "canceled", quantity = 3))).isEqualTo(200)
            assertThat(accountStatus(admin)).isEqualTo("lapsed")
            admin.post("/api/v1/clients", mapOf("name" to "New", "currency" to "EUR")).expectError(402, "account_read_only")
            exportWorks(admin)
            // The billing page says how many may stay.
            assertThat(admin.get("/api/v1/billing/subscription").expect(200)["free_plan_seats"].asInt()).isEqualTo(1)

            // Deactivating someone works, and only that: no other change, not even with it, and nobody back.
            admin.patch("/api/v1/people/$aroha", mapOf("name" to "Aroha T")).expectError(402, "account_read_only")
            admin.patch("/api/v1/people/$aroha", mapOf("is_active" to false, "name" to "Aroha T")).expectError(402, "account_read_only")
            MockPaddle.calls.clear()
            admin.patch("/api/v1/people/$aroha", mapOf("is_active" to false)).expect(200)
            assertThat(admin.get("/api/v1/people/$aroha")["name"].asText()).isEqualTo("Aroha")
            // Two can still sign in, one more than the free plan: still read-only.
            assertThat(accountStatus(admin)).isEqualTo("lapsed")
            admin.patch("/api/v1/people/$aroha", mapOf("is_active" to true)).expectError(402, "account_read_only")
            exportWorks(admin)

            // Back within the free plan: active again at once, and the job leaves it that way.
            admin.patch("/api/v1/people/$hemi", mapOf("is_active" to false)).expect(200)
            assertThat(accountStatus(admin)).isEqualTo("active")
            billing().lapseDue()
            assertThat(accountStatus(admin)).isEqualTo("active")
            admin.post("/api/v1/clients", mapOf("name" to "Back to work", "currency" to "EUR")).expect(201)
            exportWorks(admin)
            // Nothing was sent to Paddle on the way.
            assertThat(paddleCalls(account)).isEmpty()
        }
    }

    @Test
    fun `an invitation accepted after the subscription ended is refused and changes nothing`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        // Before, the person got in and the account turned read-only for everyone (decision record 0021).
        val admin = signup(accountName = "Rewarewa Ltd")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val email = uniqueEmail("tane")
        val tane = confirmed(admin, HttpMethod.POST, "/api/v1/people", mapOf("name" to "Tane", "email" to email, "role" to "member")).expect(201).id()
        val token = mail.linkToken(email)
        withFreePlanOfOne {
            assertThat(deliver(subscriptionEvent(account, type = "subscription.canceled", status = "canceled", quantity = 1))).isEqualTo(200)
            assertThat(accountStatus(admin)).isEqualTo("active")

            MockPaddle.calls.clear()
            val refused = accept(token).expectError(409, "invitation_needs_team_plan")
            assertThat(refused["message"].asText()).contains("Team plan", "Ask the person who invited you")
            // Nothing changed: the account, the invitation, no new user, nothing charged.
            assertThat(accountStatus(admin)).isEqualTo("active")
            assertThat(admin.get("/api/v1/people/$tane")["status"].asText()).isEqualTo("invited")
            assertThat(tx.system { dsl.fetchExists(USERS, USERS.EMAIL.eq(email)) }).isFalse()
            billing().lapseDue()
            assertThat(accountStatus(admin)).isEqualTo("active")
            admin.post("/api/v1/clients", mapOf("name" to "Still working", "currency" to "EUR")).expect(201)
            exportWorks(admin)
            assertThat(paddleCalls(account)).isEmpty()

            // Once Team runs again, the same link works, and the person is billed from that moment.
            assertThat(deliver(subscriptionEvent(account, type = "subscription.resumed", status = "active", quantity = 1))).isEqualTo(200)
            accept(token).expect(200)
            assertThat(seatChanges(account).single().body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
            exportWorks(admin)
        }
    }

    @Test
    fun `an invitation waiting when Team starts is billed from its acceptance`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        withFreePlanOf(2) {
            val admin = signup(accountName = "Akeake Ltd")
            val account = admin.accountId!!
            val email = uniqueEmail("pita")
            admin.post("/api/v1/people", mapOf("name" to "Pita", "email" to email, "role" to "member")).expect(201)

            // The billing page shows it before checkout, as waiting and not billed; starting Team is the yes to it.
            val free = admin.get("/api/v1/billing/subscription").expect(200)
            assertThat(free["invitations_pending"].asInt()).isEqualTo(1)
            assertThat(admin.post("/api/v1/billing/checkout", mapOf("interval" to "year")).expect(200)["quantity"].asInt()).isEqualTo(1)
            assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)

            MockPaddle.calls.clear()
            accept(mail.linkToken(email)).expect(200)
            assertThat(seatChanges(account).single().body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
        }
    }

    @Test
    fun `when Paddle is down at an accept, the person still gets in and the seat sync bills the seat once`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        val admin = signup(accountName = "Kotukutuku Ltd")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val email = uniqueEmail("aperahama")
        val id = confirmed(admin, HttpMethod.POST, "/api/v1/people", mapOf("name" to "Aperahama", "email" to email, "role" to "member")).expect(201).id()

        MockPaddle.calls.clear()
        MockPaddle.whileDown { accept(mail.linkToken(email)).expect(200) }
        assertThat(admin.get("/api/v1/people/$id")["status"].asText()).isEqualTo("active")
        assertThat(seatChanges(account).filter { it.status == 200 }).isEmpty()
        assertThat(seatsBilled(admin)).isEqualTo(1)

        // The job catches up once, and running it again sends nothing more.
        billing().syncSeats()
        billing().syncSeats()
        val sent = seatChanges(account).filter { it.status == 200 }
        assertThat(sent).hasSize(1)
        assertThat(sent.single().body!!["items"][0]["quantity"].asInt()).isEqualTo(2)
        assertThat(seatsBilled(admin)).isEqualTo(2)
    }

    @Test
    fun `an accepted invitation that rolls back charges nothing`() {
        assumeTrue(props.edition == Edition.CLOUD, "billing is part of the cloud edition only")
        // Second review, 5 October 2026: Paddle was told inside the accept's transaction, so a
        // rollback could bill someone who can't sign in.
        val admin = signup(accountName = "Whau Ltd")
        val account = admin.accountId!!
        assertThat(deliver(subscriptionEvent(account, quantity = 1))).isEqualTo(200)
        val email = uniqueEmail("moana")
        val id = confirmed(admin, HttpMethod.POST, "/api/v1/people", mapOf("name" to "Moana", "email" to email, "role" to "member")).expect(201).id()
        val token = mail.linkToken(email)

        MockPaddle.calls.clear()
        val auth = context.getBean(AuthService::class.java)
        assertThatThrownBy {
            tx.run {
                auth.acceptInvite(token, null, "correct horse battery", null, null)
                throw IllegalStateException("rolled back on purpose")
            }
        }.hasMessage("rolled back on purpose")
        assertThat(seatChanges(account)).isEmpty()
        assertThat(admin.get("/api/v1/people/$id")["status"].asText()).isEqualTo("invited")

        // The invitation still works, and is billed once.
        accept(token).expect(200)
        assertThat(seatChanges(account)).hasSize(1)
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
