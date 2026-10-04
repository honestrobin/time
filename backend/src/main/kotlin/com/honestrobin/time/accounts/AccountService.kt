// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.AccountsRecord
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.security.Role
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.Currency
import java.util.UUID

data class AccountView(
    val id: UUID,
    val name: String,
    val timezone: String,
    val weekStart: Int,
    val defaultCurrency: String,
    val timeRoundingMinutes: Int,
    /** up (to the next step) or nearest. */
    val timeRoundingMode: String,
    val locale: String,
    val legalName: String?,
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
    val iban: String?,
    val bic: String?,
    val approvalsEnabled: Boolean,
    val timesheetRemindersEnabled: Boolean,
    /** How durations are shown: hm (1:30) or decimal (1.50). */
    val durationFormat: String,
    /** Members must use two-factor sign-in to work in this account. */
    val requireTwoFactor: Boolean,
    /** active, lapsed (read-only, export works) or pending_deletion. */
    val status: String,
    /** When a scheduled deletion happens; null unless the account is pending deletion. */
    val deletesAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AccountUpdate(
    val name: String? = null,
    val timezone: String? = null,
    val weekStart: Int? = null,
    val defaultCurrency: String? = null,
    val timeRoundingMinutes: Int? = null,
    val timeRoundingMode: String? = null,
    val locale: String? = null,
    val legalName: String? = null,
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
    val iban: String? = null,
    val bic: String? = null,
    val approvalsEnabled: Boolean? = null,
    val timesheetRemindersEnabled: Boolean? = null,
    val durationFormat: String? = null,
    val requireTwoFactor: Boolean? = null,
)

data class NewAccount(
    val name: String,
    val timezone: String? = null,
    val defaultCurrency: String? = null,
    val locale: String? = null,
    val weekStart: Int? = null,
)

@Service
class AccountService(private val dsl: DSLContext, private val recentAuth: RecentAuth) {

    /** Creates an account with [userId] as its first admin. Runs as system: the tenant does not exist yet. */
    @Transactional
    fun create(userId: UUID, input: NewAccount): UUID = DbContext.system {
        val fields = validate(AccountUpdate(name = input.name, timezone = input.timezone, weekStart = input.weekStart, defaultCurrency = input.defaultCurrency, locale = input.locale))
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne() ?: throw NotFoundException("User")
        val account = dsl.newRecord(ACCOUNTS).apply {
            name = input.name.trim()
            timezone = fields.timezone ?: "UTC"
            defaultCurrency = fields.defaultCurrency ?: "EUR"
            locale = fields.locale ?: user.locale
            fields.weekStart?.let { weekStart = it.toShort() }
        }
        account.store()
        dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ACCOUNT_ID, account.id)
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.NAME, user.name)
            .set(MEMBERSHIPS.EMAIL, user.email)
            .set(MEMBERSHIPS.ROLE, Role.ADMIN.sql)
            .set(MEMBERSHIPS.STATUS, "active")
            .set(MEMBERSHIPS.CAN_SEE_RATES, true)
            .set(MEMBERSHIPS.CAN_MANAGE_PROJECTS, true)
            .set(MEMBERSHIPS.CAN_MANAGE_INVOICES, true)
            .execute()
        account.id
    }

    @Transactional(readOnly = true)
    fun get(member: Member): AccountView = view(load(member.accountId))

    @Transactional
    fun update(member: Member, update: AccountUpdate): AccountView {
        member.requireAdmin()
        member.requireWritable()
        val u = validate(update)
        val r = load(member.accountId)
        // Bank details print on every invoice: changing them is how payments get redirected.
        val iban = u.iban?.ifBlank { null }?.replace(" ", "")?.uppercase()
        val bic = u.bic?.ifBlank { null }?.uppercase()
        // A browser session, not an API token: a token can be phished through device sign-in.
        if ((u.iban != null && iban != r.iban) || (u.bic != null && bic != r.bic)) recentAuth.require()
        u.name?.let { r.name = it.trim() }
        u.timezone?.let { r.timezone = it }
        u.weekStart?.let { r.weekStart = it.toShort() }
        u.defaultCurrency?.let { r.defaultCurrency = it }
        u.timeRoundingMinutes?.let { r.timeRoundingMinutes = it.toShort() }
        u.timeRoundingMode?.let { r.timeRoundingMode = it }
        u.locale?.let { r.locale = it }
        u.legalName?.let { r.legalName = it.ifBlank { null } }
        u.addressLine1?.let { r.addressLine1 = it.ifBlank { null } }
        u.addressLine2?.let { r.addressLine2 = it.ifBlank { null } }
        u.postalCode?.let { r.postalCode = it.ifBlank { null } }
        u.city?.let { r.city = it.ifBlank { null } }
        u.region?.let { r.region = it.ifBlank { null } }
        u.countryCode?.let { r.countryCode = it.ifBlank { null }?.uppercase() }
        u.vatId?.let { r.vatId = it.ifBlank { null }?.replace(" ", "")?.uppercase() }
        u.companyRegNo?.let { r.companyRegNo = it.ifBlank { null } }
        u.peppolScheme?.let { r.peppolScheme = it.ifBlank { null } }
        u.peppolId?.let { r.peppolId = it.ifBlank { null } }
        if (u.iban != null) r.iban = iban
        if (u.bic != null) r.bic = bic
        u.approvalsEnabled?.let { r.approvalsEnabled = it }
        u.timesheetRemindersEnabled?.let { r.timesheetRemindersEnabled = it }
        u.durationFormat?.let { r.durationFormat = it }
        u.requireTwoFactor?.takeIf { it != r.requireTwoFactor }?.let { require ->
            if (require) {
                // So nobody turns it on and locks themselves out.
                val own = DbContext.system { dsl.select(USERS.TOTP_ENABLED_AT).from(USERS).where(USERS.ID.eq(member.userId)).fetchOne()?.value1() }
                if (own == null) throw ConflictException("enable_two_factor_first", "Turn on two-factor sign-in for yourself first, under Profile.")
            } else {
                recentAuth.require()
            }
            r.requireTwoFactor = require
        }
        r.store()
        return view(r)
    }

    private fun load(id: UUID): AccountsRecord = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Account")

    private fun validate(u: AccountUpdate): AccountUpdate {
        val errors = mutableMapOf<String, String>()
        u.name?.let { if (it.isBlank() || it.length > 200) errors["name"] = "Enter an account name (max 200 characters)" else com.honestrobin.time.platform.web.Names.problem(it, 200)?.let { e -> errors["name"] = e } }
        // The legal name heads every invoice email's subject.
        u.legalName?.let { com.honestrobin.time.platform.web.Names.problem(it, 200)?.let { e -> errors["legal_name"] = e } }
        u.timezone?.let { tz -> if (runCatching { ZoneId.of(tz) }.exceptionOrNull() is DateTimeException) errors["timezone"] = "Unknown time zone" }
        u.weekStart?.let { if (it !in 1..7) errors["week_start"] = "Week start must be 1 (Monday) to 7 (Sunday)" }
        u.defaultCurrency?.let { if (runCatching { Currency.getInstance(it) }.isFailure) errors["default_currency"] = "Unknown currency" }
        u.timeRoundingMinutes?.let { if (it !in setOf(0, 6, 15, 30)) errors["time_rounding_minutes"] = "Rounding must be 0, 6, 15 or 30 minutes" }
        u.countryCode?.let { if (it.isNotBlank() && !Regex("^[A-Za-z]{2}$").matches(it)) errors["country_code"] = "Use a two-letter country code" }
        u.durationFormat?.let { if (it !in setOf("hm", "decimal")) errors["duration_format"] = "Use hm or decimal" }
        u.timeRoundingMode?.let { if (it !in setOf("up", "nearest")) errors["time_rounding_mode"] = "Use up or nearest" }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return u
    }

    companion object {
        fun view(r: AccountsRecord) = AccountView(
            id = r.id, name = r.name, timezone = r.timezone, weekStart = r.weekStart.toInt(), defaultCurrency = r.defaultCurrency,
            timeRoundingMinutes = r.timeRoundingMinutes.toInt(), timeRoundingMode = r.timeRoundingMode, locale = r.locale, legalName = r.legalName,
            addressLine1 = r.addressLine1, addressLine2 = r.addressLine2, postalCode = r.postalCode, city = r.city, region = r.region,
            countryCode = r.countryCode, vatId = r.vatId, companyRegNo = r.companyRegNo, peppolScheme = r.peppolScheme,
            peppolId = r.peppolId, iban = r.iban, bic = r.bic, approvalsEnabled = r.approvalsEnabled,
            timesheetRemindersEnabled = r.timesheetRemindersEnabled, durationFormat = r.durationFormat, requireTwoFactor = r.requireTwoFactor, status = r.status,
            deletesAt = r.deletionRequestedAt?.plus(com.honestrobin.time.export.DELETION_GRACE), createdAt = r.createdAt, updatedAt = r.updatedAt,
        )
    }
}
