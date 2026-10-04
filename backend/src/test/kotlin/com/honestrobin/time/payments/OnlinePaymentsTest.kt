// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.payments

import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockStripe
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Instant
import java.util.UUID

/** Spec §5.6 and AT-3.4, against MockStripe. */
class OnlinePaymentsTest : IntegrationTest() {

    private fun sentInvoice(admin: TestClient, currency: String = "EUR", unitPrice: Long = 125_000): Pair<UUID, String> {
        val client = admin.post("/api/v1/clients", mapOf("name" to "Client ${UUID.randomUUID().toString().take(6)}", "currency" to currency)).expect(201).id()
        val inv = admin.post("/api/v1/invoices", mapOf("client_id" to client, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to unitPrice)))).expect(201).id()
        val sent = admin.post("/api/v1/invoices/$inv/mark_sent").expect(200)
        return inv to sent["public_url"].asText().substringAfterLast("/")
    }

    private fun event(invoice: UUID, amount: Long, currency: String = "eur", reference: String = "pi_${UUID.randomUUID()}", account: String? = null, accountId: UUID? = null) = """
        {"id":"evt_${UUID.randomUUID()}","type":"checkout.session.completed","created":${Instant.now().epochSecond}${account?.let { ",\"account\":\"$it\"" } ?: ""},
         "data":{"object":{"id":"cs_test_x","payment_status":"paid","amount_total":$amount,"currency":"$currency","payment_intent":"$reference",
         "metadata":{"invoice_id":"$invoice"${accountId?.let { ",\"account_id\":\"$it\"" } ?: ""}}}}}
    """.trimIndent()

    /** Connects [admin]'s account to the Stripe account [acct] through Stripe Connect. */
    private fun connect(admin: TestClient, acct: String) {
        val url = admin.post("/api/v1/payments/stripe/connect").expect(200)["url"].asText()
        val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        MockStripe.connectedAccount = acct
        assertThat(admin.get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "good-code")).headers["Location"]!!.single()).endsWith("stripe=connected")
    }

    private fun signed(payload: String, secret: String): Map<String, String> {
        val t = Instant.now().epochSecond
        return mapOf("Stripe-Signature" to "t=$t,v1=${StripeClient.sign(secret, t, payload)}")
    }

    private fun webhook(path: String, payload: String, headers: Map<String, String>) =
        client().request(HttpMethod.POST, path, payload, headers = headers)

    @Test
    fun `connected with its own key, a client pays on the invoice page and the webhook marks the invoice paid, with no fee taken (AT-3_4)`() {
        val admin = signup()
        admin.post("/api/v1/payments/stripe/key", mapOf("secret_key" to "sk_test_wrong", "webhook_secret" to "whsec_x")).expectError(422, "validation_failed")
        val status = admin.post("/api/v1/payments/stripe/key", mapOf("secret_key" to MockStripe.ACCOUNT_KEY, "webhook_secret" to "whsec_own")).expect(200)
        assertThat(status["connected"].asBoolean()).isTrue()
        assertThat(status["account_name"].asText()).isEqualTo("Fjord & Pine Studio")
        assertThat(status["webhook_url"].asText()).endsWith("/webhooks/stripe/${admin.accountId}")
        // The key is stored, never shown again.
        assertThat(admin.get("/api/v1/payments/stripe").raw).doesNotContain(MockStripe.ACCOUNT_KEY)

        val (invoice, token) = sentInvoice(admin)
        val page = client()
        assertThat(page.get("/api/v1/public/invoices/$token").expect(200)["can_pay_online"].asBoolean()).isTrue()
        MockStripe.calls.clear()
        val checkout = page.post("/api/v1/public/invoices/$token/checkout").expect(200)
        assertThat(checkout["url"].asText()).startsWith("https://checkout.stripe.test/")
        val call = MockStripe.calls.single { it.path == "/v1/checkout/sessions" }
        assertThat(call.authorization).isEqualTo("Bearer ${MockStripe.ACCOUNT_KEY}")
        assertThat(call.stripeAccount).isNull()
        assertThat(call.form["line_items[0][price_data][unit_amount]"]).isEqualTo("125000")
        assertThat(call.form["line_items[0][price_data][currency]"]).isEqualTo("eur")
        assertThat(call.form["metadata[invoice_id]"]).isEqualTo(invoice.toString())
        assertThat(call.form.keys).noneMatch { it.contains("application_fee") }

        val payload = event(invoice, 125_000, reference = "pi_paid_1")
        webhook("/webhooks/stripe/${admin.accountId}", payload, mapOf("Stripe-Signature" to "t=1,v1=bad")).expect(400)
        webhook("/webhooks/stripe/${admin.accountId}", payload, signed(payload, "whsec_own")).expect(200)
        val paid = admin.get("/api/v1/invoices/$invoice").expect(200)
        assertThat(paid["state"].asText()).isEqualTo("paid")
        assertThat(paid["payments"].single()["method"].asText()).isEqualTo("stripe")
        assertThat(paid["payments"].single()["amount"].asLong()).isEqualTo(125_000)

        // Stripe retries events; the payment is recorded once.
        webhook("/webhooks/stripe/${admin.accountId}", payload, signed(payload, "whsec_own")).expect(200)
        assertThat(admin.get("/api/v1/invoices/$invoice")["payments"]).hasSize(1)
        page.post("/api/v1/public/invoices/$token/checkout").expectError(409, "not_payable")
    }

    @Test
    fun `with Stripe Connect the account authorises once, and payments go to its own Stripe account`() {
        val admin = signup()
        val url = admin.post("/api/v1/payments/stripe/connect").expect(200)["url"].asText()
        assertThat(url).contains("client_id=${MockStripe.CLIENT_ID}").contains("scope=read_write")
        val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]

        val bad = admin.get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "expired"))
        assertThat(bad.status).isEqualTo(302)
        assertThat(bad.headers["Location"]!!.single()).endsWith("stripe=error")
        MockStripe.connectedAccount = "acct_connect_${UUID.randomUUID().toString().take(8)}"
        // Only the browser that started can finish (security review, 4 October 2026): otherwise
        // another business's admin could be led to attach their Stripe account to this workspace.
        assertThat(signup(accountName = "Other Co").get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "good-code")).headers["Location"]!!.single()).endsWith("stripe=error")
        assertThat(client().get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "good-code")).headers["Location"]!!.single()).endsWith("stripe=error")
        assertThat(admin.get("/api/v1/payments/stripe")["connected"].asBoolean()).isFalse()
        val ok = admin.get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "good-code"))
        assertThat(ok.headers["Location"]!!.single()).endsWith("stripe=connected")
        assertThat(admin.get("/api/v1/payments/stripe")["mode"].asText()).isEqualTo("connect")
        // A state can't be used twice.
        assertThat(admin.get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "good-code")).headers["Location"]!!.single()).endsWith("stripe=error")

        val (invoice, token) = sentInvoice(admin, currency = "JPY", unitPrice = 50_000)
        MockStripe.calls.clear()
        client().post("/api/v1/public/invoices/$token/checkout").expect(200)
        val call = MockStripe.calls.single { it.path == "/v1/checkout/sessions" }
        assertThat(call.authorization).isEqualTo("Bearer ${MockStripe.PLATFORM_KEY}")
        assertThat(call.stripeAccount).isEqualTo(MockStripe.connectedAccount)
        assertThat(call.form["line_items[0][price_data][unit_amount]"]).isEqualTo("50000")

        val payload = event(invoice, 50_000, currency = "jpy", account = MockStripe.connectedAccount)
        webhook("/webhooks/stripe", payload, signed(payload, MockStripe.PLATFORM_WEBHOOK_SECRET)).expect(200)
        assertThat(admin.get("/api/v1/invoices/$invoice")["state"].asText()).isEqualTo("paid")
    }

    @Test
    fun `one Stripe account connected to two workspaces still records each payment on the right one`() {
        // Security review, 4 October 2026: the webhook looked up a single connection and failed
        // when there were two, so payments silently stopped being recorded.
        val acct = "acct_shared_${UUID.randomUUID().toString().take(8)}"
        val first = signup(accountName = "First Co")
        val second = signup(accountName = "Second Co")
        connect(first, acct)
        connect(second, acct)
        val (invoice, _) = sentInvoice(second, unitPrice = 10_000)
        val payload = event(invoice, 10_000, account = acct, accountId = second.accountId)
        webhook("/webhooks/stripe", payload, signed(payload, MockStripe.PLATFORM_WEBHOOK_SECRET)).expect(200)
        assertThat(second.get("/api/v1/invoices/$invoice")["state"].asText()).isEqualTo("paid")
    }

    @Test
    fun `disconnecting ends Honest Robin's access at Stripe, unless another workspace still uses the account`() {
        // Security review, 4 October 2026: disconnecting only forgot the connection here.
        val acct = "acct_leaving_${UUID.randomUUID().toString().take(8)}"
        val first = signup(accountName = "First Co")
        val second = signup(accountName = "Second Co")
        connect(first, acct)
        connect(second, acct)
        MockStripe.calls.clear()
        val shared = first.delete("/api/v1/payments/stripe").expect(200)
        assertThat(shared["revoked"].asBoolean()).isFalse()
        assertThat(shared["note"].asText()).contains("Another workspace")
        assertThat(MockStripe.calls).noneMatch { it.path == "/oauth/deauthorize" }
        assertThat(first.get("/api/v1/payments/stripe")["connected"].asBoolean()).isFalse()
        assertThat(second.delete("/api/v1/payments/stripe").expect(200)["revoked"].asBoolean()).isTrue()
        assertThat(MockStripe.calls.single { it.path == "/oauth/deauthorize" }.form).containsEntry("stripe_user_id", acct).containsEntry("client_id", MockStripe.CLIENT_ID)

        // Stripe refuses: disconnected here all the same, and the answer says what to do there.
        val third = signup(accountName = "Third Co")
        val refused = "acct_refused_${UUID.randomUUID().toString().take(8)}"
        connect(third, refused)
        MockStripe.refuseDeauthorize += refused
        val answer = third.delete("/api/v1/payments/stripe").expect(200)
        assertThat(answer["revoked"].asBoolean()).isFalse()
        assertThat(answer["note"].asText()).contains("didn't confirm")
        assertThat(third.get("/api/v1/payments/stripe")["connected"].asBoolean()).isFalse()

        // With its own key there's nothing Honest Robin can revoke; it says so.
        val own = signup(accountName = "Own Key Co")
        own.post("/api/v1/payments/stripe/key", mapOf("secret_key" to MockStripe.ACCOUNT_KEY, "webhook_secret" to "whsec_own")).expect(200)
        val forgotten = own.delete("/api/v1/payments/stripe").expect(200)
        assertThat(forgotten["revoked"].asBoolean()).isFalse()
        assertThat(forgotten["note"].asText()).contains("roll it")
    }

    @Test
    fun `when the owner removes Honest Robin in Stripe, the workspace stops saying it's connected`() {
        val admin = signup()
        val acct = "acct_removed_${UUID.randomUUID().toString().take(8)}"
        connect(admin, acct)
        val payload = """{"id":"evt_${UUID.randomUUID()}","type":"account.application.deauthorized","account":"$acct","created":${Instant.now().epochSecond},
            "data":{"object":{"id":"${MockStripe.CLIENT_ID}","object":"application"}}}"""
        webhook("/webhooks/stripe", payload, signed(payload, "whsec_not_ours")).expect(400)
        assertThat(admin.get("/api/v1/payments/stripe")["connected"].asBoolean()).isTrue()
        webhook("/webhooks/stripe", payload, signed(payload, MockStripe.PLATFORM_WEBHOOK_SECRET)).expect(200)
        assertThat(admin.get("/api/v1/payments/stripe")["connected"].asBoolean()).isFalse()
    }

    @Test
    fun `without a connection the page offers no online payment`() {
        val admin = signup()
        val (_, token) = sentInvoice(admin)
        assertThat(client().get("/api/v1/public/invoices/$token")["can_pay_online"].asBoolean()).isFalse()
        client().post("/api/v1/public/invoices/$token/checkout").expectError(409, "no_online_payments")
    }

    @Test
    fun `amounts convert to Stripe's units, including currencies Stripe treats differently`() {
        assertThat(StripeClient.toStripeAmount(12_345, "EUR")).isEqualTo(12_345)
        assertThat(StripeClient.toStripeAmount(5_000, "JPY")).isEqualTo(5_000)
        // docs.stripe.com/currencies, "Special cases": HUF and TWD are charged with two decimals;
        // ISK and UGX have none, but Stripe takes them as amounts ending in 00 (5 ISK is 500).
        assertThat(StripeClient.toStripeAmount(1_234_500, "HUF")).isEqualTo(1_234_500)
        assertThat(StripeClient.fromStripeAmount(1_234_500, "HUF")).isEqualTo(1_234_500)
        assertThat(StripeClient.toStripeAmount(80_045, "TWD")).isEqualTo(80_045)
        assertThat(StripeClient.toStripeAmount(5, "ISK")).isEqualTo(500)
        assertThat(StripeClient.fromStripeAmount(500, "ISK")).isEqualTo(5)
        assertThat(StripeClient.toStripeAmount(50_000, "UGX")).isEqualTo(5_000_000)
        assertThat(StripeClient.toStripeAmount(12_340, "KWD")).isEqualTo(12_340)
        assertThat(StripeClient.verifySignature("{}", "t=${Instant.now().epochSecond - 3600},v1=${StripeClient.sign("s", Instant.now().epochSecond - 3600, "{}")}", "s")).isFalse()
    }
}
