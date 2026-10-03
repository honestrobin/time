// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.platform.web.idCursor
import com.honestrobin.time.accounts.Access
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.tables.records.ClientContactsRecord
import com.honestrobin.time.db.tables.records.ClientsRecord
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ETags
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Page
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.platform.web.Versioned
import com.honestrobin.time.platform.web.clampLimit
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Currency
import java.util.UUID

data class ContactView(
    val id: UUID,
    val clientId: UUID,
    val name: String,
    val title: String?,
    val email: String?,
    val phone: String?,
    val isInvoiceRecipient: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class ClientView(
    val id: UUID,
    val name: String,
    val currency: String,
    val addressLine1: String?,
    val addressLine2: String?,
    val postalCode: String?,
    val city: String?,
    val region: String?,
    val countryCode: String?,
    val vatId: String?,
    val companyRegNo: String?,
    val peppolScheme: String?,
    val peppolId: String?,
    /** Whether the client can receive e-invoices over Peppol, as last checked; null if never checked. */
    val peppolReachable: Boolean?,
    val peppolCheckedAt: Instant?,
    val notes: String?,
    val isActive: Boolean,
    val contacts: List<ContactView>,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Versioned {
    override val version get() = updatedAt
}

data class ClientInput(
    val name: String? = null,
    val currency: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val region: String? = null,
    val countryCode: String? = null,
    val vatId: String? = null,
    val companyRegNo: String? = null,
    val peppolScheme: String? = null,
    val peppolId: String? = null,
    val notes: String? = null,
    val isActive: Boolean? = null,
)

data class ContactInput(
    val name: String? = null,
    val title: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val isInvoiceRecipient: Boolean? = null,
)

@Service
class ClientService(private val dsl: DSLContext, private val access: Access) {

    @Transactional(readOnly = true)
    fun list(m: Member, isActive: Boolean?, cursor: String?, limit: Int?): Page<ClientView> {
        m.requireManagerOrAdmin()
        val n = clampLimit(limit, 500)
        val rows = dsl.selectFrom(CLIENTS)
            .where(if (isActive != null) CLIENTS.IS_ACTIVE.eq(isActive) else DSL.noCondition())
            .and(if (cursor != null) CLIENTS.ID.gt(idCursor(cursor)) else DSL.noCondition())
            .orderBy(CLIENTS.ID)
            .limit(n + 1)
            .fetch()
        val contacts = contactsFor(rows.map { it.id })
        return Page.of(rows.map { view(it, contacts[it.id].orEmpty()) }, n) { it.id.toString() }
    }

    @Transactional(readOnly = true)
    fun get(m: Member, id: UUID): ClientView {
        m.requireManagerOrAdmin()
        val r = load(id)
        return view(r, contactsFor(listOf(id))[id].orEmpty())
    }

    @Transactional
    fun create(m: Member, input: ClientInput): ClientView {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        if (input.name.isNullOrBlank()) throw ValidationException("name", "Enter a client name")
        val currency = input.currency ?: dsl.select(ACCOUNTS.DEFAULT_CURRENCY).from(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!!.value1()
        val r = dsl.newRecord(CLIENTS).apply {
            accountId = m.accountId
            this.currency = currency
        }
        apply(r, input)
        storeUnique(r)
        return view(r, emptyList())
    }

    @Transactional
    fun update(m: Member, id: UUID, input: ClientInput): ClientView {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        val r = load(id)
        ETags.checkIfMatch(r.updatedAt)
        apply(r, input)
        storeUnique(r)
        return view(r, contactsFor(listOf(id))[id].orEmpty())
    }

    @Transactional
    fun delete(m: Member, id: UUID) {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        val r = load(id)
        ETags.checkIfMatch(r.updatedAt)
        if (dsl.fetchExists(PROJECTS, PROJECTS.CLIENT_ID.eq(id))) {
            throw ConflictException("client_in_use", "This client has projects. Archive it instead.")
        }
        r.delete()
    }

    @Transactional
    fun addContact(m: Member, clientId: UUID, input: ContactInput): ContactView {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        load(clientId)
        val r = dsl.newRecord(CLIENT_CONTACTS).apply {
            accountId = m.accountId
            this.clientId = clientId
        }
        applyContact(r, input)
        r.store()
        return contactView(r)
    }

    @Transactional
    fun updateContact(m: Member, clientId: UUID, contactId: UUID, input: ContactInput): ContactView {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        val r = loadContact(clientId, contactId)
        ETags.checkIfMatch(r.updatedAt)
        applyContact(r, input)
        r.store()
        return contactView(r)
    }

    @Transactional
    fun deleteContact(m: Member, clientId: UUID, contactId: UUID) {
        m.requireWritable()
        access.requireCanCreateProjects(m)
        loadContact(clientId, contactId).delete()
    }

    fun load(id: UUID): ClientsRecord = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Client")

    private fun loadContact(clientId: UUID, id: UUID): ClientContactsRecord =
        dsl.selectFrom(CLIENT_CONTACTS).where(CLIENT_CONTACTS.ID.eq(id)).and(CLIENT_CONTACTS.CLIENT_ID.eq(clientId)).fetchOne()
            ?: throw NotFoundException("Contact")

    private fun storeUnique(r: ClientsRecord) {
        try {
            r.store()
        } catch (e: DuplicateKeyException) {
            throw ValidationException("name", "A client with this name already exists")
        }
    }

    private fun apply(r: ClientsRecord, i: ClientInput) {
        val errors = mutableMapOf<String, String>()
        i.name?.let { if (it.isBlank()) errors["name"] = "Enter a client name" else r.name = it.trim() }
        i.currency?.let { if (runCatching { Currency.getInstance(it) }.isFailure) errors["currency"] = "Unknown currency" else r.currency = it }
        i.countryCode?.let {
            if (it.isNotBlank() && !Regex("^[A-Za-z]{2}$").matches(it)) errors["country_code"] = "Use a two-letter country code"
            r.countryCode = it.ifBlank { null }?.uppercase()
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        i.addressLine1?.let { r.addressLine1 = it.ifBlank { null } }
        i.addressLine2?.let { r.addressLine2 = it.ifBlank { null } }
        i.postalCode?.let { r.postalCode = it.ifBlank { null } }
        i.city?.let { r.city = it.ifBlank { null } }
        i.region?.let { r.region = it.ifBlank { null } }
        i.vatId?.let { r.vatId = it.ifBlank { null }?.replace(" ", "")?.uppercase() }
        i.companyRegNo?.let { r.companyRegNo = it.ifBlank { null } }
        val peppolBefore = r.peppolScheme to r.peppolId
        i.peppolScheme?.let { r.peppolScheme = it.ifBlank { null } }
        i.peppolId?.let { r.peppolId = it.ifBlank { null } }
        // A different Peppol ID needs checking again.
        if ((r.peppolScheme to r.peppolId) != peppolBefore) {
            r.peppolReachable = null
            r.peppolCheckedAt = null
        }
        i.notes?.let { r.notes = it.ifBlank { null } }
        i.isActive?.let {
            r.isActive = it
            r.archivedAt = if (it) null else (r.archivedAt ?: Instant.now())
        }
    }

    private fun applyContact(r: ClientContactsRecord, i: ContactInput) {
        i.name?.let { if (it.isBlank()) throw ValidationException("name", "Enter the contact's name") else r.name = it.trim() }
        if (r.name == null) throw ValidationException("name", "Enter the contact's name")
        i.title?.let { r.title = it.ifBlank { null } }
        i.email?.let {
            if (it.isNotBlank() && !it.contains('@')) throw ValidationException("email", "Enter a valid email address")
            r.email = it.ifBlank { null }?.trim()
        }
        i.phone?.let { r.phone = it.ifBlank { null } }
        i.isInvoiceRecipient?.let { r.isInvoiceRecipient = it }
    }

    private fun contactsFor(clientIds: List<UUID>): Map<UUID, List<ContactView>> =
        if (clientIds.isEmpty()) emptyMap()
        else dsl.selectFrom(CLIENT_CONTACTS).where(CLIENT_CONTACTS.CLIENT_ID.`in`(clientIds)).orderBy(CLIENT_CONTACTS.NAME)
            .fetch().map(::contactView).groupBy { it.clientId }

    companion object {
        fun view(r: ClientsRecord, contacts: List<ContactView>) = ClientView(
            r.id, r.name, r.currency, r.addressLine1, r.addressLine2, r.postalCode, r.city, r.region, r.countryCode,
            r.vatId, r.companyRegNo, r.peppolScheme, r.peppolId, r.peppolReachable, r.peppolCheckedAt, r.notes, r.isActive, contacts, r.createdAt, r.updatedAt,
        )

        fun contactView(r: ClientContactsRecord) =
            ContactView(r.id, r.clientId, r.name, r.title, r.email, r.phone, r.isInvoiceRecipient, r.createdAt, r.updatedAt)
    }
}

@RestController
@RequestMapping("/api/v1/clients")
@Tag(name = "clients")
class ClientController(private val clients: ClientService) {
    @GetMapping
    fun list(
        @RequestParam(name = "is_active", required = false) isActive: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = clients.list(Current.member(), isActive, cursor, limit)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = clients.get(Current.member(), id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: ClientInput) = clients.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody body: ClientInput) = clients.update(Current.member(), id, body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = clients.delete(Current.member(), id)

    @PostMapping("/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    fun addContact(@PathVariable id: UUID, @RequestBody body: ContactInput) = clients.addContact(Current.member(), id, body)

    @PatchMapping("/{id}/contacts/{contactId}")
    fun updateContact(@PathVariable id: UUID, @PathVariable contactId: UUID, @RequestBody body: ContactInput) =
        clients.updateContact(Current.member(), id, contactId, body)

    @DeleteMapping("/{id}/contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteContact(@PathVariable id: UUID, @PathVariable contactId: UUID) = clients.deleteContact(Current.member(), id, contactId)
}
