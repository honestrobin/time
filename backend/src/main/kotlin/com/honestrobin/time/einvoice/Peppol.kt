// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.einvoice

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EINVOICE_TRANSMISSIONS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.db.tables.records.IntegrationsRecord
import com.honestrobin.time.invoicing.InvoiceService
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.crypto.SecretBox
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Peppol participant identifier schemes: the ISO 6523 code we store (BT-34/BT-49 scheme, as in
 * "0088" or "9930") and the name Storecove uses for it. Only codes listed here can be sent
 * through Storecove. (VERIFY against Storecove's receiver identifier list.)
 */
object PeppolSchemes {
    val STORECOVE: Map<String, String> = mapOf(
        "0002" to "FR:SIRENE", "0007" to "SE:ORGNR", "0009" to "FR:SIRET", "0037" to "FI:OVT", "0060" to "DUNS",
        "0088" to "GLN", "0106" to "NL:KVK", "0151" to "AU:ABN", "0184" to "DK:DIGST", "0190" to "NL:OINO",
        "0192" to "NO:ORG", "0195" to "SG:UEN", "0204" to "DE:LWID", "0208" to "BE:EN", "0210" to "IT:CF",
        "0211" to "IT:IVA", "9906" to "IT:VAT", "9915" to "AT:VAT", "9920" to "ES:VAT", "9925" to "BE:VAT",
        "9930" to "DE:VAT", "9944" to "NL:VAT", "9955" to "SE:VAT", "9957" to "FR:VAT",
    )

    fun storecove(icd: String?): String? = icd?.let { STORECOVE[it.trim()] }
}

/** Sending e-invoices through a Peppol access point (spec §7.3). Storecove first; others plug in here. */
interface EInvoiceProvider {
    val name: String

    /** Registers the sender at the provider; returns the provider's id for it. */
    fun registerLegalEntity(apiKey: String, party: SenderParty): String

    fun addIdentifier(apiKey: String, legalEntityId: String, scheme: String, identifier: String)

    /** Whether the receiver can get invoices over Peppol. */
    fun lookupRecipient(apiKey: String, scheme: String, identifier: String): Boolean

    /** Sends a Peppol BIS invoice; returns the provider's reference for the transmission. */
    fun send(apiKey: String, legalEntityId: String, ubl: ByteArray, scheme: String, identifier: String, idempotencyKey: String): String
}

data class SenderParty(val name: String, val line1: String, val city: String, val zip: String, val country: String, val tenantId: String)

class ProviderException(val status: Int, message: String) : RuntimeException(message)

@ConfigurationProperties(prefix = "honestrobin.storecove")
data class StorecoveSettings(
    val apiBaseUrl: String = "https://api.storecove.com/api/v2",
    /** The operator's key (Honest Robin Cloud): accounts can send without a Storecove contract of their own. */
    val platformApiKey: String = "",
    /** Secret Storecove sends with its webhooks to the platform endpoint (as a custom header). */
    val platformWebhookSecret: String = "",
) {
    val platformEnabled: Boolean get() = platformApiKey.isNotBlank()
}

/** Storecove's REST API (VERIFY: shapes from Storecove's OpenAPI 2.0.1 and docs, October 2026). */
@Component
class StorecoveProvider(private val settings: StorecoveSettings, private val json: ObjectMapper) : EInvoiceProvider {
    override val name = "storecove"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun registerLegalEntity(apiKey: String, party: SenderParty): String =
        call(
            apiKey, "POST", "/legal_entities",
            mapOf("party_name" to party.name, "line1" to party.line1, "city" to party.city, "zip" to party.zip, "country" to party.country, "tenant_id" to party.tenantId),
        )["id"].asText()

    override fun addIdentifier(apiKey: String, legalEntityId: String, scheme: String, identifier: String) {
        call(apiKey, "POST", "/legal_entities/$legalEntityId/peppol_identifiers", mapOf("superscheme" to "iso6523-actorid-upis", "scheme" to scheme, "identifier" to identifier))
    }

    override fun lookupRecipient(apiKey: String, scheme: String, identifier: String): Boolean =
        call(
            apiKey, "POST", "/discovery/receives",
            mapOf("documentTypes" to listOf("invoice"), "network" to "peppol", "metaScheme" to "iso6523-actorid-upis", "scheme" to scheme, "identifier" to identifier),
        )["code"]?.asText() == "OK"

    override fun send(apiKey: String, legalEntityId: String, ubl: ByteArray, scheme: String, identifier: String, idempotencyKey: String): String =
        call(
            apiKey, "POST", "/document_submissions",
            mapOf(
                "legalEntityId" to legalEntityId.toLongOrNull(),
                "idempotencyGuid" to idempotencyKey,
                "routing" to mapOf("eIdentifiers" to listOf(mapOf("scheme" to scheme, "id" to identifier))),
                // Our own Peppol BIS document, as validated, not re-generated by the provider.
                "document" to mapOf(
                    "documentType" to "invoice",
                    "rawDocumentData" to mapOf(
                        "document" to Base64.getEncoder().encodeToString(ubl),
                        "parse" to false,
                        "documentTypeId" to "busdox-docid-qns::urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1",
                        "processId" to "cenbii-procid-ubl::urn:fdc:peppol.eu:2017:poacc:billing:01:1.0",
                    ),
                ),
            ),
        )["guid"].asText()

    private fun call(apiKey: String, method: String, path: String, body: Any?): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(settings.apiBaseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json").header("Accept", "application/json")
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw ProviderException(0, "Storecove could not be reached: ${e.message}")
        }
        val parsed = runCatching { json.readTree(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) {
            val detail = parsed?.let { it["message"]?.asText() ?: it["errors"]?.toString() } ?: "HTTP ${response.statusCode()}"
            throw ProviderException(response.statusCode(), "Storecove: $detail")
        }
        return parsed ?: json.createObjectNode()
    }
}

data class PeppolStatus(
    val connected: Boolean,
    /** api_key (the account's own Storecove contract) or connect (through Honest Robin's). */
    val mode: String?,
    val legalEntityId: String?,
    val platformAvailable: Boolean,
    /** Where Storecove should send delivery updates for an own contract. */
    val webhookUrl: String?,
    val webhookSecret: String?,
)

data class PeppolConnectInput(
    /** Your own Storecove API key; leave out to send through Honest Robin's contract, where offered. */
    val apiKey: String? = null,
)

data class ClientPeppolCheck(val reachable: Boolean, val checkedAt: Instant)

data class TransmissionView(
    val id: UUID,
    val provider: String,
    val format: String,
    /** queued, sent, delivered, failed or rejected. */
    val status: String,
    val providerRef: String?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Sending invoices over Peppol: connect the account (its own provider key, or the operator's
 * contract on Honest Robin Cloud), check whether a client is reachable, send, and track
 * delivery from the provider's webhooks.
 */
@Service
class PeppolService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val provider: EInvoiceProvider,
    private val einvoices: EInvoiceService,
    private val invoices: InvoiceService,
    private val settings: StorecoveSettings,
    private val secrets: SecretBox,
    private val props: HonestRobinProperties,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun integration(): IntegrationsRecord? = dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.KIND.eq(provider.name)).fetchOne()

    private fun credentials(r: IntegrationsRecord): Map<String, String> =
        r.credentialsEncrypted?.let { json.readValue(secrets.decrypt(it), Map::class.java).entries.associate { (k, v) -> k.toString() to v.toString() } }.orEmpty()

    private fun apiKey(r: IntegrationsRecord): String = if (r.mode == "connect") settings.platformApiKey else credentials(r)["api_key"].orEmpty()

    fun status(m: Member): PeppolStatus {
        invoices.requireAccess(m)
        return tx.run {
            val r = integration()?.takeIf { it.status == "connected" }
            PeppolStatus(
                connected = r != null, mode = r?.mode, legalEntityId = r?.externalAccountId, platformAvailable = settings.platformEnabled,
                webhookUrl = if (r?.mode == "api_key") "${props.baseUrl}/webhooks/storecove/${m.accountId}" else null,
                // Whoever has it can forge delivery reports, so only admins see it.
                webhookSecret = if (r?.mode == "api_key" && m.isAdmin) r.let { credentials(it)["webhook_secret"] } else null,
            )
        }
    }

    /** Registers the account at the provider, with its address and Peppol ID. */
    fun connect(m: Member, input: PeppolConnectInput): PeppolStatus {
        m.requireWritable()
        m.requireAdmin()
        val own = input.apiKey?.trim()?.ifBlank { null }
        if (own == null && !settings.platformEnabled) throw ValidationException("api_key", "Paste your Storecove API key")
        val key = own ?: settings.platformApiKey
        val account = tx.run { dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!! }
        val missing = listOfNotNull(
            "your address".takeIf { account.addressLine1.isNullOrBlank() || account.city.isNullOrBlank() || account.postalCode.isNullOrBlank() || account.countryCode.isNullOrBlank() },
            "your Peppol ID".takeIf { account.peppolId.isNullOrBlank() },
        )
        if (missing.isNotEmpty()) throw ValidationException("account", "Add ${missing.joinToString(" and ")} in Account settings first.")
        val scheme = PeppolSchemes.storecove(account.peppolScheme)
            ?: throw ValidationException("account", "Sending isn't available yet for Peppol IDs with scheme ${account.peppolScheme}.")
        val entity = try {
            provider.registerLegalEntity(
                key,
                SenderParty(account.legalName?.ifBlank { null } ?: account.name, account.addressLine1, account.city, account.postalCode, account.countryCode.uppercase(), m.accountId.toString()),
            ).also { provider.addIdentifier(key, it, scheme, account.peppolId) }
        } catch (e: ProviderException) {
            throw ValidationException("api_key", if (e.status == 401 || e.status == 403) "Storecove didn't accept this API key" else e.message ?: "Storecove refused")
        }
        tx.run {
            val r = integration() ?: dsl.newRecord(INTEGRATIONS).apply { accountId = m.accountId; kind = provider.name }
            r.mode = if (own != null) "api_key" else "connect"
            r.status = "connected"
            r.externalAccountId = entity
            r.displayName = "Storecove"
            r.credentialsEncrypted = own?.let { secrets.encrypt(json.writeValueAsString(mapOf("api_key" to it, "webhook_secret" to Tokens.generate(24)))) }
            r.connectedBy = m.membershipId
            r.connectedAt = Instant.now()
            r.lastError = null
            r.store()
        }
        return status(m)
    }

    fun disconnect(m: Member) {
        m.requireWritable()
        m.requireAdmin()
        tx.run { integration()?.delete() }
    }

    /** Asks the provider whether the client can receive over Peppol, and remembers the answer. */
    fun checkClient(m: Member, clientId: UUID): ClientPeppolCheck {
        m.requireManagerOrAdmin()
        val (client, r) = tx.run {
            (dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(clientId)).fetchOne() ?: throw NotFoundException("Client")) to
                (integration()?.takeIf { it.status == "connected" } ?: throw ConflictException("peppol_not_connected", "Connect Peppol sending in Invoice settings first"))
        }
        val reachable = if (client.peppolId.isNullOrBlank()) {
            false
        } else {
            val scheme = PeppolSchemes.storecove(client.peppolScheme) ?: throw ValidationException("peppol_scheme", "Sending isn't available yet for scheme ${client.peppolScheme}.")
            try {
                provider.lookupRecipient(apiKey(r), scheme, client.peppolId)
            } catch (e: ProviderException) {
                throw ApiException(HttpStatus.BAD_GATEWAY, "provider_error", e.message ?: "Storecove couldn't be asked")
            }
        }
        val now = Instant.now()
        tx.run {
            dsl.update(CLIENTS).set(CLIENTS.PEPPOL_REACHABLE, reachable).set(CLIENTS.PEPPOL_CHECKED_AT, now).where(CLIENTS.ID.eq(clientId)).execute()
        }
        return ClientPeppolCheck(reachable, now)
    }

    /** Sends an issued invoice as Peppol BIS; the transmission is recorded either way. */
    fun send(m: Member, invoiceId: UUID): TransmissionView {
        m.requireWritable()
        invoices.requireAccess(m)
        val (invoice, r, ubl) = tx.run {
            val inv = invoices.load(invoiceId)
            val r = integration()?.takeIf { it.status == "connected" } ?: throw ConflictException("peppol_not_connected", "Connect Peppol sending in Invoice settings first")
            Triple(inv, r, einvoices.generate(inv, EInvoiceFormat.PEPPOL).bytes)
        }
        val client = tx.run { dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(invoice.clientId)).fetchOne()!! }
        val scheme = PeppolSchemes.storecove(client.peppolScheme) ?: throw ValidationException("peppol_scheme", "Sending isn't available yet for scheme ${client.peppolScheme}.")
        val t = tx.run {
            dsl.newRecord(EINVOICE_TRANSMISSIONS).apply {
                accountId = m.accountId; this.invoiceId = invoiceId; this.provider = this@PeppolService.provider.name; format = EInvoiceFormat.PEPPOL.key; status = "queued"
                store()
            }
        }
        try {
            val ref = provider.send(apiKey(r), r.externalAccountId, ubl, scheme, client.peppolId, t.id.toString())
            t.status = "sent"
            t.providerRef = ref
        } catch (e: ProviderException) {
            t.status = "failed"
            t.lastError = e.message
        }
        t.attempts = t.attempts + 1
        tx.run {
            dsl.update(EINVOICE_TRANSMISSIONS).set(EINVOICE_TRANSMISSIONS.STATUS, t.status).set(EINVOICE_TRANSMISSIONS.PROVIDER_REF, t.providerRef)
                .set(EINVOICE_TRANSMISSIONS.LAST_ERROR, t.lastError).set(EINVOICE_TRANSMISSIONS.ATTEMPTS, t.attempts).where(EINVOICE_TRANSMISSIONS.ID.eq(t.id)).execute()
        }
        return tx.run { view(dsl.selectFrom(EINVOICE_TRANSMISSIONS).where(EINVOICE_TRANSMISSIONS.ID.eq(t.id)).fetchOne()!!) }
    }

    fun transmissions(m: Member, invoiceId: UUID): List<TransmissionView> {
        invoices.requireAccess(m)
        return tx.run {
            dsl.selectFrom(EINVOICE_TRANSMISSIONS).where(EINVOICE_TRANSMISSIONS.INVOICE_ID.eq(invoiceId)).orderBy(EINVOICE_TRANSMISSIONS.CREATED_AT.desc()).fetch().map(::view)
        }
    }

    /**
     * Delivery updates from the provider. On an account's own contract the secret is that
     * account's; on the operator's contract it's the platform secret. (VERIFY the event shape.)
     */
    fun webhook(accountId: UUID?, payload: String, secret: String?): Boolean {
        val expected = if (accountId == null) {
            settings.platformWebhookSecret
        } else {
            tx.system { dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.ACCOUNT_ID.eq(accountId)).and(INTEGRATIONS.KIND.eq(provider.name)).fetchOne() }
                ?.let { credentials(it)["webhook_secret"] }.orEmpty()
        }
        if (expected.isBlank() || secret == null || !MessageDigest.isEqual(secret.toByteArray(), expected.toByteArray())) return false
        val event = runCatching { json.readTree(payload) }.getOrNull() ?: return true
        val body = event["body"]?.takeIf { it.isTextual }?.let { runCatching { json.readTree(it.asText()) }.getOrNull() } ?: event
        val guid = body["document_guid"]?.asText() ?: body["guid"]?.asText() ?: return true
        val status = when (body["event"]?.asText() ?: body["status"]?.asText()) {
            "succeeded", "delivered" -> "delivered"
            "failed", "no_action_taken" -> "failed"
            "rejected" -> "rejected"
            else -> return true
        }
        tx.system {
            val t = dsl.selectFrom(EINVOICE_TRANSMISSIONS).where(EINVOICE_TRANSMISSIONS.PROVIDER_REF.eq(guid))
                .apply { if (accountId != null) and(EINVOICE_TRANSMISSIONS.ACCOUNT_ID.eq(accountId)) }.fetchOne() ?: return@system
            t.status = status
            if (status != "delivered") t.lastError = body["details"]?.toString()?.take(1000) ?: body["event"]?.asText()
            t.store()
            log.info("E-invoice transmission {} is {}", t.id, status)
        }
        return true
    }

    private fun view(t: com.honestrobin.time.db.tables.records.EinvoiceTransmissionsRecord) =
        TransmissionView(t.id, t.provider, t.format, t.status, t.providerRef, t.lastError, t.createdAt, t.updatedAt)
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "einvoicing", description = "Sending e-invoices over Peppol")
class PeppolController(private val peppol: PeppolService, private val recentAuth: RecentAuth) {
    @GetMapping("/einvoicing/peppol")
    fun status() = peppol.status(Current.member())

    @PostMapping("/einvoicing/peppol")
    @Operation(summary = "Connect Peppol sending: your own Storecove API key, or Honest Robin's contract where offered")
    fun connect(@RequestBody body: PeppolConnectInput): PeppolStatus {
        // Invoices go out over Peppol in the account's name.
        recentAuth.require()
        return peppol.connect(Current.member(), body)
    }

    @DeleteMapping("/einvoicing/peppol")
    fun disconnect(): ResponseEntity<Unit> {
        peppol.disconnect(Current.member())
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/clients/{id}/peppol_check")
    @Operation(summary = "Check whether the client can receive e-invoices over Peppol")
    fun checkClient(@PathVariable id: UUID) = peppol.checkClient(Current.member(), id)

    @PostMapping("/invoices/{id}/einvoice/send")
    @Operation(summary = "Send the invoice over Peppol")
    fun send(@PathVariable id: UUID) = peppol.send(Current.member(), id)

    @GetMapping("/invoices/{id}/einvoice/transmissions")
    fun transmissions(@PathVariable id: UUID) = peppol.transmissions(Current.member(), id)
}

@RestController
class StorecoveWebhookController(private val peppol: PeppolService) {
    @PostMapping("/webhooks/storecove")
    fun platform(@RequestBody payload: String, @RequestHeader(name = "X-Webhook-Secret", required = false) secret: String?): ResponseEntity<Unit> =
        if (peppol.webhook(null, payload, secret)) ResponseEntity.ok().build() else ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

    @PostMapping("/webhooks/storecove/{accountId}")
    fun account(@PathVariable accountId: UUID, @RequestBody payload: String, @RequestHeader(name = "X-Webhook-Secret", required = false) secret: String?): ResponseEntity<Unit> =
        if (peppol.webhook(accountId, payload, secret)) ResponseEntity.ok().build() else ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
}
