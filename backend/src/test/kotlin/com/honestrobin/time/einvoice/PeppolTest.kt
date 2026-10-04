// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.einvoice

import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockStorecove
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.util.Base64
import java.util.UUID

/** Spec §7.3 and AT-5.2: sending over Peppol through Storecove, against MockStorecove. */
class PeppolTest : IntegrationTest() {

    private class Setup(val admin: TestClient, val client: UUID, val vat: String)

    private fun setup(): Setup {
        val admin = signup(accountName = "Fjordlicht GmbH")
        admin.patch(
            "/api/v1/account",
            mapOf(
                "legal_name" to "Fjordlicht GmbH", "address_line1" to "Hafenstraße 12", "postal_code" to "20457", "city" to "Hamburg", "country_code" to "DE",
                "vat_id" to "DE123456789", "iban" to "DE89370400440532013000", "peppol_scheme" to "9930", "peppol_id" to "DE123456789",
            ),
        ).expect(200)
        // Every test client has its own VAT number, so tests don't share reachability.
        val vat = "FR" + (10_000_000_000L..99_999_999_999L).random()
        val client = admin.post(
            "/api/v1/clients",
            mapOf("name" to "Atelier Bleu SARL", "currency" to "EUR", "address_line1" to "12 rue de la Paix", "postal_code" to "75002", "city" to "Paris", "country_code" to "FR",
                "vat_id" to vat, "peppol_scheme" to "9957", "peppol_id" to vat),
        ).expect(201).id()
        return Setup(admin, client, vat)
    }

    private fun issued(s: Setup): UUID {
        val id = s.admin.post(
            "/api/v1/invoices",
            mapOf("client_id" to s.client, "vat_mode" to "standard", "purchase_order" to "PO-7",
                "lines" to listOf(mapOf("description" to "Design", "quantity" to 10, "unit_price" to 9_500, "vat_category_code" to "S", "vat_percent" to 19))),
        ).expect(201).id()
        s.admin.post("/api/v1/invoices/$id/mark_sent").expect(200)
        return id
    }

    @Test
    fun `with its own Storecove key an account checks a client, sends, and hears back (AT-5_2)`() {
        val s = setup()
        s.admin.post("/api/v1/einvoicing/peppol", mapOf("api_key" to "sc_wrong")).expectError(422, "validation_failed")
        MockStorecove.calls.clear()
        val status = s.admin.post("/api/v1/einvoicing/peppol", mapOf("api_key" to MockStorecove.OWN_KEY)).expect(200)
        assertThat(status["connected"].asBoolean()).isTrue()
        assertThat(status["mode"].asText()).isEqualTo("api_key")
        assertThat(status["webhook_url"].asText()).endsWith("/webhooks/storecove/${s.admin.accountId}")
        val entity = MockStorecove.calls.single { it.path.endsWith("/legal_entities") }.body!!
        assertThat(entity["tenant_id"].asText()).isEqualTo(s.admin.accountId.toString())
        assertThat(MockStorecove.calls.single { it.path.endsWith("/peppol_identifiers") }.body!!["scheme"].asText()).isEqualTo("DE:VAT")
        // The key is stored, never shown.
        assertThat(s.admin.get("/api/v1/einvoicing/peppol").raw).doesNotContain(MockStorecove.OWN_KEY)

        // Reachability: not yet, then yes.
        assertThat(s.admin.post("/api/v1/clients/${s.client}/peppol_check").expect(200)["reachable"].asBoolean()).isFalse()
        MockStorecove.reachable += "FR:VAT:${s.vat}"
        assertThat(s.admin.post("/api/v1/clients/${s.client}/peppol_check").expect(200)["reachable"].asBoolean()).isTrue()

        // Send: our own validated Peppol BIS goes out as it is.
        val invoice = issued(s)
        MockStorecove.calls.clear()
        val sent = s.admin.post("/api/v1/invoices/$invoice/einvoice/send").expect(200)
        assertThat(sent["status"].asText()).isEqualTo("sent")
        val submission = MockStorecove.calls.single { it.path.endsWith("/document_submissions") }
        assertThat(submission.authorization).isEqualTo("Bearer ${MockStorecove.OWN_KEY}")
        assertThat(submission.body!!["routing"]["eIdentifiers"][0]["scheme"].asText()).isEqualTo("FR:VAT")
        assertThat(submission.body["idempotencyGuid"].asText()).isEqualTo(sent["id"].asText())
        val ubl = Base64.getDecoder().decode(submission.body["document"]["rawDocumentData"]["document"].asText()).toString(Charsets.UTF_8)
        assertThat(ubl).contains("urn:fdc:peppol.eu:2017:poacc:billing:3.0").contains("Atelier Bleu SARL")

        // Delivery comes back by webhook; only with the account's secret.
        val secret = s.admin.get("/api/v1/einvoicing/peppol")["webhook_secret"].asText()
        val event = """{"event_type":"document_submission","event":"succeeded","document_guid":"${sent["provider_ref"].asText()}"}"""
        assertThat(client().request(HttpMethod.POST, "/webhooks/storecove/${s.admin.accountId}", event, headers = mapOf("X-Webhook-Secret" to "nope")).status).isEqualTo(401)
        client().request(HttpMethod.POST, "/webhooks/storecove/${s.admin.accountId}", event, headers = mapOf("X-Webhook-Secret" to secret)).expect(200)
        assertThat(s.admin.get("/api/v1/invoices/$invoice/einvoice/transmissions").body.single()["status"].asText()).isEqualTo("delivered")
    }

    @Test
    fun `through Honest Robin's contract, failures are recorded and the platform webhook updates them`() {
        val s = setup()
        assertThat(s.admin.get("/api/v1/einvoicing/peppol")["platform_available"].asBoolean()).isTrue()
        s.admin.post("/api/v1/einvoicing/peppol", emptyMap<String, Any>()).expect(200).let { assertThat(it["mode"].asText()).isEqualTo("connect") }
        val invoice = issued(s)
        MockStorecove.refused += "FR:VAT:${s.vat}"
        val failed = s.admin.post("/api/v1/invoices/$invoice/einvoice/send").expect(200)
        assertThat(failed["status"].asText()).isEqualTo("failed")
        assertThat(failed["last_error"].asText()).contains("Receiver not found")
        MockStorecove.refused -= "FR:VAT:${s.vat}"
        val sent = s.admin.post("/api/v1/invoices/$invoice/einvoice/send").expect(200)
        assertThat(MockStorecove.calls.last { it.path.endsWith("/document_submissions") }.authorization).isEqualTo("Bearer ${MockStorecove.PLATFORM_KEY}")
        val event = """{"event_type":"document_submission","event":"failed","document_guid":"${sent["provider_ref"].asText()}","details":"Rejected by receiver"}"""
        client().request(HttpMethod.POST, "/webhooks/storecove", event, headers = mapOf("X-Webhook-Secret" to MockStorecove.PLATFORM_WEBHOOK_SECRET)).expect(200)
        assertThat(s.admin.get("/api/v1/invoices/$invoice/einvoice/transmissions").body.values().map { it["status"].asText() }).containsExactly("failed", "failed")
    }

    @Test
    fun `connecting needs the account's address and a supported Peppol ID`() {
        val admin = signup()
        admin.post("/api/v1/einvoicing/peppol", mapOf("api_key" to MockStorecove.OWN_KEY)).expectError(422, "validation_failed")
        val s = setup()
        s.admin.patch("/api/v1/account", mapOf("peppol_scheme" to "9999")).expect(200)
        s.admin.post("/api/v1/einvoicing/peppol", mapOf("api_key" to MockStorecove.OWN_KEY)).expectError(422, "validation_failed")
    }
}
