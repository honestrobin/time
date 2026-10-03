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

    private fun event(invoice: UUID, amount: Long, currency: String = "eur", reference: String = "pi_${UUID.randomUUID()}", account: String? = null) = """
        {"id":"evt_${UUID.randomUUID()}","type":"checkout.session.completed","created":${Instant.now().epochSecond}${account?.let { ",\"account\":\"$it\"" } ?: ""},
         "data":{"object":{"id":"cs_test_x","payment_status":"paid","amount_total":$amount,"currency":"$currency","payment_intent":"$reference",
         "metadata":{"invoice_id":"$invoice"}}}}
    """.trimIndent()

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

        val bad = client().get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "expired"))
        assertThat(bad.status).isEqualTo(302)
        assertThat(bad.headers["Location"]!!.single()).endsWith("stripe=error")
        MockStripe.connectedAccount = "acct_connect_${UUID.randomUUID().toString().take(8)}"
        val ok = client().get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "good-code"))
        assertThat(ok.headers["Location"]!!.single()).endsWith("stripe=connected")
        assertThat(admin.get("/api/v1/payments/stripe")["mode"].asText()).isEqualTo("connect")
        // A state can't be used twice.
        assertThat(client().get("/api/v1/public/stripe/callback", mapOf("state" to state, "code" to "good-code")).headers["Location"]!!.single()).endsWith("stripe=error")

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
        assertThat(StripeClient.toStripeAmount(1_234_500, "HUF")).isEqualTo(12_345)
        assertThat(StripeClient.fromStripeAmount(12_345, "HUF")).isEqualTo(1_234_500)
        assertThat(StripeClient.toStripeAmount(12_340, "KWD")).isEqualTo(12_340)
        assertThat(StripeClient.verifySignature("{}", "t=${Instant.now().epochSecond - 3600},v1=${StripeClient.sign("s", Instant.now().epochSecond - 3600, "{}")}", "s")).isFalse()
    }
}
