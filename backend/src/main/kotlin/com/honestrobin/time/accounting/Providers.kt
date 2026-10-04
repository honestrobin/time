// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounting

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** OAuth tokens for one connected company, and which company. */
data class OAuthTokens(val access: String, val refresh: String, val expiresAt: Instant, val orgId: String, val orgName: String?)

data class Choice(val id: String, val name: String)

/** What the user maps our taxes and lines to, as offered by the accounting system. */
data class ProviderOptions(val taxCodes: List<Choice>, val items: List<Choice>, val salesAccounts: List<Choice>, val paymentAccounts: List<Choice>)

/** The account's choices for one connection. */
data class Mapping(
    /** Our tax key ("S:19", "Z:0", "tax1", "none") → the provider's tax code. */
    val taxCodes: Map<String, String> = emptyMap(),
    /** QuickBooks: the product/service every line is booked on. */
    val itemId: String? = null,
    /** Xero: the revenue account lines are booked on. */
    val salesAccount: String? = null,
    /** Where payments are deposited. */
    val paymentAccount: String? = null,
    val autoPush: Boolean = true,
)

data class CustomerData(val name: String, val email: String?, val currency: String)

data class LineData(val description: String, val quantity: BigDecimal, val unitPrice: BigDecimal, val taxCode: String)

data class InvoiceData(
    val number: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val currency: String,
    val customerId: String,
    val lines: List<LineData>,
    val discountPercent: BigDecimal?,
    val reference: String?,
)

data class PaymentData(val invoiceId: String, val customerId: String, val amount: BigDecimal, val date: LocalDate, val currency: String)

class AccountingException(val status: Int, message: String) : RuntimeException(message)

/**
 * An accounting system Honest Robin pushes invoices and payments to (spec §8). One-way in v1.
 * (VERIFY each adapter's endpoints, tax models and rate limits against current docs and a
 * sandbox company before launch.)
 */
interface AccountingProvider {
    val kind: String
    val label: String
    val configured: Boolean

    fun authorizeUrl(state: String, redirectUri: String): String

    /** Completes the OAuth flow; [params] are the callback's query parameters. */
    fun exchange(code: String, redirectUri: String, params: Map<String, String>): OAuthTokens

    fun refresh(tokens: OAuthTokens): OAuthTokens

    /** Ends Honest Robin's access to this company at the provider. */
    fun revoke(tokens: OAuthTokens)

    fun options(tokens: OAuthTokens): ProviderOptions

    fun findOrCreateCustomer(tokens: OAuthTokens, customer: CustomerData): String

    /** Fails, in words a person can act on, if invoices can't be booked with these choices. */
    fun checkMapping(mapping: Mapping) = Unit

    fun createInvoice(tokens: OAuthTokens, invoice: InvoiceData, mapping: Mapping, idempotencyKey: String): String

    fun createPayment(tokens: OAuthTokens, payment: PaymentData, mapping: Mapping, idempotencyKey: String): String
}

@ConfigurationProperties(prefix = "honestrobin.qbo")
data class QboSettings(
    val clientId: String = "",
    val clientSecret: String = "",
    val authorizeUrl: String = "https://appcenter.intuit.com/connect/oauth2",
    val tokenUrl: String = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer",
    val revokeUrl: String = "https://developer.api.intuit.com/v2/oauth2/tokens/revoke",
    /** https://sandbox-quickbooks.api.intuit.com for sandbox companies. */
    val apiBaseUrl: String = "https://quickbooks.api.intuit.com",
)

@ConfigurationProperties(prefix = "honestrobin.xero")
data class XeroSettings(
    val clientId: String = "",
    val clientSecret: String = "",
    val authorizeUrl: String = "https://login.xero.com/identity/connect/authorize",
    val tokenUrl: String = "https://identity.xero.com/connect/token",
    val connectionsUrl: String = "https://api.xero.com/connections",
    val apiBaseUrl: String = "https://api.xero.com/api.xro/2.0",
)

/** Small HTTP helper shared by the adapters. */
abstract class HttpProvider(protected val json: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    protected fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    protected fun form(url: String, basicUser: String, basicPassword: String, fields: Map<String, String>): JsonNode {
        val body = fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val auth = Base64.getEncoder().encodeToString("$basicUser:$basicPassword".toByteArray())
        return send(
            HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic $auth").header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    }

    protected fun basicJson(url: String, basicUser: String, basicPassword: String, body: Any): JsonNode {
        val auth = Base64.getEncoder().encodeToString("$basicUser:$basicPassword".toByteArray())
        return send(
            HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic $auth").header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))),
        )
    }

    protected fun call(method: String, url: String, token: String, body: Any? = null, headers: Map<String, String> = emptyMap()): JsonNode {
        val b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer $token").header("Accept", "application/json")
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        return send(b)
    }

    private fun send(b: HttpRequest.Builder): JsonNode {
        val response = try {
            http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw AccountingException(0, "could not be reached: ${e.message}")
        }
        val parsed = runCatching { json.readTree(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) throw AccountingException(response.statusCode(), errorMessage(parsed) ?: "HTTP ${response.statusCode()}")
        return parsed ?: json.createObjectNode()
    }

    protected abstract fun errorMessage(body: JsonNode?): String?

    protected fun tokens(body: JsonNode, orgId: String, orgName: String?) = OAuthTokens(
        body["access_token"].asText(), body["refresh_token"].asText(),
        Instant.now().plusSeconds(body["expires_in"]?.asLong() ?: 1800), orgId, orgName,
    )
}

/** QuickBooks Online (Intuit) accounting API v3. */
@Component
class QboProvider(private val settings: QboSettings, json: ObjectMapper) : HttpProvider(json), AccountingProvider {
    override val kind = "qbo"
    override val label = "QuickBooks Online"
    override val configured get() = settings.clientId.isNotBlank() && settings.clientSecret.isNotBlank()

    override fun authorizeUrl(state: String, redirectUri: String) =
        "${settings.authorizeUrl}?client_id=${enc(settings.clientId)}&response_type=code&scope=${enc("com.intuit.quickbooks.accounting")}" +
            "&redirect_uri=${enc(redirectUri)}&state=${enc(state)}"

    override fun exchange(code: String, redirectUri: String, params: Map<String, String>): OAuthTokens {
        val realm = params["realmId"] ?: throw AccountingException(400, "QuickBooks didn't say which company")
        val body = form(settings.tokenUrl, settings.clientId, settings.clientSecret, mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri))
        val t = tokens(body, realm, null)
        val name = runCatching { api(t, "GET", "/companyinfo/$realm")["CompanyInfo"]["CompanyName"].asText() }.getOrNull()
        return t.copy(orgName = name)
    }

    override fun refresh(tokens: OAuthTokens): OAuthTokens =
        tokens(form(settings.tokenUrl, settings.clientId, settings.clientSecret, mapOf("grant_type" to "refresh_token", "refresh_token" to tokens.refresh)), tokens.orgId, tokens.orgName)

    /** Revoking the refresh token ends the connection to this company (RFC 7009). */
    override fun revoke(tokens: OAuthTokens) {
        basicJson(settings.revokeUrl, settings.clientId, settings.clientSecret, mapOf("token" to tokens.refresh))
    }

    private fun api(t: OAuthTokens, method: String, path: String, body: Any? = null, requestId: String? = null): JsonNode {
        val sep = if (path.contains('?')) "&" else "?"
        val url = "${settings.apiBaseUrl}/v3/company/${t.orgId}$path${sep}minorversion=75" + (requestId?.let { "&requestid=${enc(it)}" } ?: "")
        return call(method, url, t.access, body)
    }

    private fun query(t: OAuthTokens, q: String): JsonNode = api(t, "GET", "/query?query=${enc(q)}")["QueryResponse"] ?: json.createObjectNode()

    override fun options(tokens: OAuthTokens) = ProviderOptions(
        taxCodes = query(tokens, "select * from TaxCode").path("TaxCode").values().map { Choice(it["Id"].asText(), it["Name"].asText()) },
        items = query(tokens, "select * from Item where Type = 'Service'").path("Item").values().map { Choice(it["Id"].asText(), it["Name"].asText()) },
        salesAccounts = emptyList(),
        paymentAccounts = query(tokens, "select * from Account where AccountType = 'Bank'").path("Account").values().map { Choice(it["Id"].asText(), it["Name"].asText()) },
    )

    override fun findOrCreateCustomer(tokens: OAuthTokens, customer: CustomerData): String {
        val found = query(tokens, "select * from Customer where DisplayName = '${customer.name.replace("'", "\\'")}'").path("Customer")
        if (found.size() > 0) return found[0]["Id"].asText()
        val body = buildMap<String, Any?> {
            put("DisplayName", customer.name)
            customer.email?.let { put("PrimaryEmailAddr", mapOf("Address" to it)) }
            put("CurrencyRef", mapOf("value" to customer.currency))
        }
        return api(tokens, "POST", "/customer", body)["Customer"]["Id"].asText()
    }

    override fun checkMapping(mapping: Mapping) {
        if (mapping.itemId == null) throw AccountingException(422, "Choose the QuickBooks product or service to book invoice lines on")
    }

    override fun createInvoice(tokens: OAuthTokens, invoice: InvoiceData, mapping: Mapping, idempotencyKey: String): String {
        checkMapping(mapping)
        val item = mapping.itemId!!
        val lines = invoice.lines.map { l ->
            mapOf(
                "DetailType" to "SalesItemLineDetail", "Description" to l.description,
                "Amount" to l.quantity.multiply(l.unitPrice).setScale(2, java.math.RoundingMode.HALF_UP),
                "SalesItemLineDetail" to mapOf("ItemRef" to mapOf("value" to item), "Qty" to l.quantity, "UnitPrice" to l.unitPrice, "TaxCodeRef" to mapOf("value" to l.taxCode)),
            )
        } + listOfNotNull(
            invoice.discountPercent?.takeIf { it.signum() > 0 }?.let { mapOf("DetailType" to "DiscountLineDetail", "DiscountLineDetail" to mapOf("PercentBased" to true, "DiscountPercent" to it)) },
        )
        val body = buildMap<String, Any?> {
            put("DocNumber", invoice.number)
            put("TxnDate", invoice.issueDate.toString())
            invoice.dueDate?.let { put("DueDate", it.toString()) }
            put("CustomerRef", mapOf("value" to invoice.customerId))
            put("CurrencyRef", mapOf("value" to invoice.currency))
            invoice.reference?.let { put("PrivateNote", it) }
            put("Line", lines)
        }
        return api(tokens, "POST", "/invoice", body, idempotencyKey)["Invoice"]["Id"].asText()
    }

    override fun createPayment(tokens: OAuthTokens, payment: PaymentData, mapping: Mapping, idempotencyKey: String): String {
        val body = buildMap<String, Any?> {
            put("CustomerRef", mapOf("value" to payment.customerId))
            put("TotalAmt", payment.amount)
            put("TxnDate", payment.date.toString())
            put("CurrencyRef", mapOf("value" to payment.currency))
            mapping.paymentAccount?.let { put("DepositToAccountRef", mapOf("value" to it)) }
            put("Line", listOf(mapOf("Amount" to payment.amount, "LinkedTxn" to listOf(mapOf("TxnId" to payment.invoiceId, "TxnType" to "Invoice")))))
        }
        return api(tokens, "POST", "/payment", body, idempotencyKey)["Payment"]["Id"].asText()
    }

    override fun errorMessage(body: JsonNode?): String? =
        body?.path("Fault")?.path("Error")?.firstOrNull()?.let { listOfNotNull(it["Message"]?.asText(), it["Detail"]?.asText()).joinToString(": ") }
            ?: body?.get("error_description")?.asText() ?: body?.get("error")?.asText()
}

/** Xero accounting API 2.0. */
@Component
class XeroProvider(private val settings: XeroSettings, json: ObjectMapper) : HttpProvider(json), AccountingProvider {
    override val kind = "xero"
    override val label = "Xero"
    override val configured get() = settings.clientId.isNotBlank() && settings.clientSecret.isNotBlank()

    override fun authorizeUrl(state: String, redirectUri: String) =
        "${settings.authorizeUrl}?response_type=code&client_id=${enc(settings.clientId)}&redirect_uri=${enc(redirectUri)}" +
            "&scope=${enc("offline_access accounting.transactions accounting.contacts accounting.settings.read")}&state=${enc(state)}"

    override fun exchange(code: String, redirectUri: String, params: Map<String, String>): OAuthTokens {
        val body = form(settings.tokenUrl, settings.clientId, settings.clientSecret, mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri))
        val access = body["access_token"].asText()
        // Only the organisations authorised just now: the person may have connected others
        // before, for other workspaces.
        val event = authEventId(access)
        val orgs = call("GET", settings.connectionsUrl + (event?.let { "?authEventId=${enc(it)}" } ?: ""), access).values()
            .filter { it["tenantType"]?.asText() == "ORGANISATION" }
        val org = orgs.singleOrNull() ?: run {
            if (orgs.isEmpty()) throw AccountingException(400, "Xero didn't give access to an organisation")
            // Choosing one would be a guess. Those were all authorised just now, so remove them.
            if (event != null) orgs.forEach { runCatching { call("DELETE", "${settings.connectionsUrl}/${enc(it["id"].asText())}", access) } }
            throw AccountingException(400, "Xero gave access to more than one organisation; connect again and choose only this workspace's")
        }
        return tokens(body, org["tenantId"].asText(), org["tenantName"]?.asText())
    }

    /** The sign-in that issued [accessToken] (a JWT), which /connections can filter on. */
    private fun authEventId(accessToken: String): String? = runCatching {
        json.readTree(Base64.getUrlDecoder().decode(accessToken.split('.')[1]))["authentication_event_id"]?.asText()
    }.getOrNull()

    /**
     * Removes this organisation's connection only. Revoking the refresh token would also end
     * the person's connections to other organisations, which other workspaces may use.
     */
    override fun revoke(tokens: OAuthTokens) {
        call("GET", settings.connectionsUrl, tokens.access).values().filter { it["tenantId"]?.asText() == tokens.orgId }
            .forEach { call("DELETE", "${settings.connectionsUrl}/${enc(it["id"].asText())}", tokens.access) }
    }

    override fun refresh(tokens: OAuthTokens): OAuthTokens =
        tokens(form(settings.tokenUrl, settings.clientId, settings.clientSecret, mapOf("grant_type" to "refresh_token", "refresh_token" to tokens.refresh)), tokens.orgId, tokens.orgName)

    private fun api(t: OAuthTokens, method: String, path: String, body: Any? = null, idempotencyKey: String? = null): JsonNode =
        call(method, settings.apiBaseUrl + path, t.access, body, buildMap {
            put("Xero-tenant-id", t.orgId)
            idempotencyKey?.let { put("Idempotency-Key", it) }
        })

    override fun options(tokens: OAuthTokens): ProviderOptions {
        val accounts = api(tokens, "GET", "/Accounts").path("Accounts").filter { it["Status"]?.asText() == "ACTIVE" }
        return ProviderOptions(
            taxCodes = api(tokens, "GET", "/TaxRates").path("TaxRates").filter { it["Status"]?.asText() == "ACTIVE" }.map { Choice(it["TaxType"].asText(), it["Name"].asText()) },
            items = emptyList(),
            salesAccounts = accounts.filter { it["Type"]?.asText() in setOf("REVENUE", "SALES") }.map { Choice(it["Code"].asText(), "${it["Code"].asText()} ${it["Name"].asText()}") },
            paymentAccounts = accounts.filter { it["Type"]?.asText() == "BANK" }.map { Choice(it["AccountID"].asText(), it["Name"].asText()) },
        )
    }

    override fun findOrCreateCustomer(tokens: OAuthTokens, customer: CustomerData): String {
        val where = "Name==\"${customer.name.replace("\"", "\\\"")}\""
        val found = api(tokens, "GET", "/Contacts?where=${enc(where)}").path("Contacts")
        if (found.size() > 0) return found[0]["ContactID"].asText()
        val contact = buildMap<String, Any?> {
            put("Name", customer.name)
            customer.email?.let { put("EmailAddress", it) }
            put("DefaultCurrency", customer.currency)
        }
        return api(tokens, "POST", "/Contacts", mapOf("Contacts" to listOf(contact)))["Contacts"][0]["ContactID"].asText()
    }

    override fun checkMapping(mapping: Mapping) {
        if (mapping.salesAccount == null) throw AccountingException(422, "Choose the Xero revenue account to book invoice lines on")
    }

    override fun createInvoice(tokens: OAuthTokens, invoice: InvoiceData, mapping: Mapping, idempotencyKey: String): String {
        checkMapping(mapping)
        val account = mapping.salesAccount!!
        val lines = invoice.lines.map { l ->
            buildMap<String, Any?> {
                put("Description", l.description)
                put("Quantity", l.quantity)
                put("UnitAmount", l.unitPrice)
                put("AccountCode", account)
                put("TaxType", l.taxCode)
                invoice.discountPercent?.takeIf { it.signum() > 0 }?.let { put("DiscountRate", it) }
            }
        }
        val body = buildMap<String, Any?> {
            put("Type", "ACCREC")
            put("Contact", mapOf("ContactID" to invoice.customerId))
            put("InvoiceNumber", invoice.number)
            invoice.reference?.let { put("Reference", it) }
            put("Date", invoice.issueDate.toString())
            invoice.dueDate?.let { put("DueDate", it.toString()) }
            put("CurrencyCode", invoice.currency)
            put("LineAmountTypes", "Exclusive")
            put("Status", "AUTHORISED")
            put("LineItems", lines)
        }
        return api(tokens, "PUT", "/Invoices", mapOf("Invoices" to listOf(body)), idempotencyKey)["Invoices"][0]["InvoiceID"].asText()
    }

    override fun createPayment(tokens: OAuthTokens, payment: PaymentData, mapping: Mapping, idempotencyKey: String): String {
        val account = mapping.paymentAccount ?: throw AccountingException(422, "Choose the Xero bank account payments go into")
        val body = mapOf("Invoice" to mapOf("InvoiceID" to payment.invoiceId), "Account" to mapOf("AccountID" to account), "Date" to payment.date.toString(), "Amount" to payment.amount)
        return api(tokens, "PUT", "/Payments", mapOf("Payments" to listOf(body)), idempotencyKey)["Payments"][0]["PaymentID"].asText()
    }

    override fun errorMessage(body: JsonNode?): String? =
        body?.path("Elements")?.firstOrNull()?.path("ValidationErrors")?.firstOrNull()?.get("Message")?.asText()
            ?: body?.get("Message")?.asText() ?: body?.get("Detail")?.asText() ?: body?.get("error")?.asText()
}
