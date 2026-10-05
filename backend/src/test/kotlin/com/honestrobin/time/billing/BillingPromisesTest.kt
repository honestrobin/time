// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.billing

import com.honestrobin.time.accounts.SeatCounter
import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.edition.Edition
import com.honestrobin.time.platform.mail.MailLimits
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.Role
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockPaddle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.transaction.PlatformTransactionManager
import java.io.File
import java.util.UUID

/**
 * What Honest Robin Cloud bills for, and how often: seats only, monthly or yearly (the Robin's
 * Code, "No surprise bills" and "You can always leave"). Each test fails if its promise breaks by
 * accident; none of them can stop it being broken on purpose. The scans of the code run in both
 * editions; what needs Paddle runs in the cloud edition, against MockPaddle.
 */
class BillingPromisesTest : IntegrationTest() {
    @Autowired lateinit var props: HonestRobinProperties

    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var limits: MailLimits

    /** Main code by path, read as PromiseGuardsTest reads it. */
    private fun sources(): Map<String, String> {
        val root = File("src/main/kotlin")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    /**
     * "Nothing is metered for billing": Paddle hears only the number of people who can sign in.
     * That only seats depend on the plan is PromiseGuardsTest's; this is what reaches Paddle. The
     * mail limits against abuse are counted apart from billing, and never reach it.
     */
    @Test
    fun `only seats are billed, and the mail limits never are`() {
        val code = sources()
        val billing = code.filterKeys { it.startsWith(BILLING) }
        assertThat(billing).describedAs("the billing package").isNotEmpty()

        // Only the billing package talks to Paddle.
        assertThat(code.filter { (path, text) -> !path.startsWith(BILLING) && ("PaddleClient" in text || "paddle.com" in text) }.keys)
            .describedAs("main code outside the billing package that talks to Paddle").isEmpty()
        // It asks Paddle for nothing that could charge for anything but seats: no one-off charge,
        // no transaction, no other price, no usage. A new request fails this until it's listed here.
        val client = code.getValue("${BILLING}Paddle.kt")
        val requests = Regex("call\\(\\s*\"([A-Z]+)\",\\s*\"([^\"]+)\"").findAll(client).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toList()
        assertThat(requests).describedAs("requests the app sends Paddle").containsExactlyInAnyOrderElementsOf(PADDLE_REQUESTS.map { it.first })
        // The one count in them is the seat count, in the one list of items (updateQuantity).
        assertThat(Regex("\"items\"|\"quantity\"").findAll(client).map { it.value }.toList()).containsExactly("\"items\"", "\"quantity\"")

        // The mail limits and billing don't know each other.
        val mailCode = code.filterKeys { it.startsWith("com/honestrobin/time/platform/mail/") }
        assertThat(mailCode).describedAs("the mail package").isNotEmpty()
        assertThat(mailCode.filterValues { text -> BILLING_WORDS.any { it in text } }.keys).describedAs("mail code that names billing").isEmpty()
        assertThat(billing.filterValues { text -> MAIL_LIMIT_WORDS.any { it in text } }.keys).describedAs("billing code that reads the mail limits").isEmpty()

        // What follows needs Paddle, which only Honest Robin Cloud has.
        if (props.edition != Edition.CLOUD) return

        val admin = signup(accountName = "Rimu Studio")
        val accountId = admin.accountId!!
        invite(admin)
        admin.post("/api/v1/people", mapOf("name" to "Ana Pending", "email" to uniqueEmail("ana"), "role" to "member")).expect(201)
        // Checkout counts the two people who can sign in. The invitation counts once it's accepted.
        assertThat(admin.post("/api/v1/billing/checkout", mapOf("interval" to "month")).expect(200)["quantity"].asInt()).isEqualTo(2)

        // A Team subscription, which Paddle bills for one seat so far.
        val subscription = "sub_seats_only_${UUID.randomUUID()}"
        tx.system {
            dsl.insertInto(SUBSCRIPTIONS).set(SUBSCRIPTIONS.ACCOUNT_ID, accountId).set(SUBSCRIPTIONS.EXTERNAL_ID, subscription)
                .set(SUBSCRIPTIONS.EXTERNAL_PRICE_ID, MockPaddle.MONTHLY_PRICE).set(SUBSCRIPTIONS.BILLING_INTERVAL, "month").set(SUBSCRIPTIONS.STATUS, "active")
                .set(SUBSCRIPTIONS.SEATS, 1).set(SUBSCRIPTIONS.CURRENCY, "EUR").set(SUBSCRIPTIONS.LOCKED_UNIT_PRICE_MINOR, 850L)
                .execute()
        }
        val configured = limits.invoiceRecipientsPerDay
        try {
            // Invoice emails up to the day's limit, and one past it.
            limits.invoiceRecipientsPerDay = 2
            val clientId = createClient(admin)
            fun invoice() = admin.post(
                "/api/v1/invoices",
                mapOf("client_id" to clientId, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 10_000))),
            ).expect(201).id()
            admin.post("/api/v1/invoices/${invoice()}/send", mapOf("to" to listOf("a@client.test", "b@client.test"))).expect(200)
            admin.post("/api/v1/invoices/${invoice()}/send", mapOf("to" to listOf("c@client.test"))).expectError(429, "invoice_email_limit")

            // None of it reached Paddle, or the subscription.
            assertThat(MockPaddle.calls.filter { subscription in it.path }).isEmpty()
            val row = tx.system { dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.EXTERNAL_ID.eq(subscription)).fetchOne()!! }
            assertThat(listOf(row.seats, row.lockedUnitPriceMinor, row.status)).containsExactly(1, 850L, "active")

            // The seat sync tells Paddle the people who can sign in, on the subscription's own
            // price, and nothing else: not the invitation, not the emails.
            context.getBean(BillingService::class.java).syncSeats()
            val sent = MockPaddle.calls.filter { it.path == "/subscriptions/$subscription" }
            assertThat(sent).hasSize(1)
            val body = sent.single().body!!
            assertThat(body.properties().map { it.key }).containsExactlyInAnyOrder("items", "proration_billing_mode")
            assertThat(body["items"].values().map { it["price_id"].asText() to it["quantity"].asInt() }).containsExactly(MockPaddle.MONTHLY_PRICE to 2)
            assertThat(admin.get("/api/v1/billing/subscription").expect(200)["seats_billed"].asInt()).isEqualTo(2)
        } finally {
            limits.invoiceRecipientsPerDay = configured
            tx.system { dsl.deleteFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.EXTERNAL_ID.eq(subscription)).execute() }
        }
    }

    /**
     * "Pay monthly or yearly, your choice; no plan is yearly-only." Billing can't be switched on
     * without a monthly price, and checkout opens on it whatever the yearly price is.
     */
    @Test
    fun `monthly is always offered, and no plan is yearly-only`() {
        // Billing is on only with a monthly price: a yearly price alone leaves it off.
        val keys = PaddleSettings(apiKey = "pdl_key", webhookSecret = "pdl_secret")
        assertThat(keys.copy(teamAnnualPriceId = "pri_year").configured).describedAs("a yearly price only").isFalse()
        assertThat(keys.copy(teamMonthlyPriceId = "pri_month").configured).describedAs("a monthly price only").isTrue()
        assertThat(keys.copy(teamMonthlyPriceId = "pri_month", teamAnnualPriceId = "pri_year").configured).describedAs("both").isTrue()

        // What follows needs Paddle, which only Honest Robin Cloud has.
        if (props.edition != Edition.CLOUD) return

        // With both prices, as Honest Robin Cloud runs: the billing page lists both, and checkout opens on either.
        val admin = signup(accountName = "Kahikatea Studio")
        val page = admin.get("/api/v1/billing/subscription").expect(200)
        assertThat(page["billing_available"].asBoolean()).isTrue()
        assertThat(page["prices"].values().map { it["interval"].asText() }).containsExactlyInAnyOrder("month", "year")
        assertThat(admin.post("/api/v1/billing/checkout", mapOf("interval" to "month")).expect(200)["price_id"].asText()).isEqualTo(MockPaddle.MONTHLY_PRICE)
        assertThat(admin.post("/api/v1/billing/checkout", mapOf("interval" to "year")).expect(200)["price_id"].asText()).isEqualTo(MockPaddle.ANNUAL_PRICE)

        // Without a yearly price, checkout still opens monthly; yearly is the one refused.
        val monthlyOnly = BillingService(
            dsl, tx, context.getBean(PaddleSettings::class.java).copy(teamAnnualPriceId = ""), context.getBean(BillingPrices::class.java),
            context.getBean(SeatCounter::class.java), context.getBean(FreePlan::class.java), context.getBean(Funnel::class.java), mapper, clock,
            context.getBean(Mailer::class.java), props, context.getBean(PlatformTransactionManager::class.java),
        )
        val membership = UUID.fromString(admin.get("/api/v1/me").expect(200)["current_membership_id"].asText())
        val member = Member(membership, admin.accountId!!, admin.userId!!, Role.ADMIN, "Ada Admin", true, true, true, "active")
        fun checkout(interval: String) = DbContext.forAccount(admin.accountId!!) { tx.run { monthlyOnly.checkout(member, CheckoutRequest(interval)) } }
        assertThat(checkout("month").priceId).isEqualTo(MockPaddle.MONTHLY_PRICE)
        assertThatThrownBy { checkout("year") }.isInstanceOf(ValidationException::class.java)
    }
}

private const val BILLING = "com/honestrobin/time/billing/"

/** Every request the app may send Paddle, and why. None of them charges for anything but seats. */
private val PADDLE_REQUESTS = listOf(
    "PATCH /subscriptions/\$subscriptionId" to "the seat count, on the subscription's own price (updateQuantity)",
    "PATCH /subscriptions/\$subscriptionId" to "taking back a cancellation before it happens (removeScheduledChange)",
    "GET /subscriptions/\$subscriptionId" to "whether a payment is past due, before cancelling",
    "POST /subscriptions/\$subscriptionId/cancel" to "cancelling now: a deleted account, a second subscription, or one past due",
    "POST /subscriptions/\$subscriptionId/cancel" to "cancelling at the end of the period that's paid for",
    "POST /customers/\$customerId/portal-sessions" to "a link to Paddle's customer portal",
)

/** What would show the mail limits reading billing. */
private val BILLING_WORDS = listOf("com.honestrobin.time.billing", "Paddle", "SUBSCRIPTIONS", "BillingService", "SeatGate", "SeatCounter", "seats")

/** What would show billing reading the mail limits. */
private val MAIL_LIMIT_WORDS = listOf("MailLimits", "OutboundMail", "RateLimiter", "invoice-mail", "invite-mail")
