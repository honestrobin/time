// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.platform.mail.OutboundMail
import com.honestrobin.time.auth.ApiTokenService
import com.honestrobin.time.auth.ApiTokenView
import com.honestrobin.time.auth.AuthService
import com.honestrobin.time.auth.CreatedApiToken
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.web.ForbiddenException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class MyAccount(val id: UUID, val name: String, val role: String, val status: String, val membershipId: UUID)

data class MeView(
    val id: UUID,
    val email: String,
    val name: String,
    val locale: String,
    val isInstanceAdmin: Boolean,
    val hasPassword: Boolean,
    val emailVerified: Boolean,
    /** Whether this instance asks for a confirmed address before an account emails anyone. */
    val emailVerificationRequired: Boolean,
    val accounts: List<MyAccount>,
    /** Account this request was resolved against (header or the only account). */
    val currentAccountId: UUID?,
    val currentMembershipId: UUID?,
    val role: String?,
    val permissions: Permissions?,
    val twoFactorEnabled: Boolean,
    /** The current account requires two-factor sign-in, which this person must set up before anything else. */
    val twoFactorSetupRequired: Boolean,
)

data class Permissions(val canSeeRates: Boolean, val canManageProjects: Boolean, val canManageInvoices: Boolean)

data class MeUpdate(val name: String? = null, val locale: String? = null)
data class PasswordChange(val currentPassword: String? = null, val newPassword: String)
data class NewApiToken(val name: String, val scopes: List<String> = listOf("read", "write"), val expiresAt: Instant? = null)

@Service
class MeService(private val dsl: DSLContext, private val outbound: OutboundMail) {
    @Transactional(readOnly = true)
    fun me(): MeView {
        val principal = Current.principal()
        val member = Current.memberOrNull()
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(principal.userId)).fetchOne()!!
        val accounts = DbContext.system {
            dsl.select(ACCOUNTS.ID, ACCOUNTS.NAME, ACCOUNTS.STATUS, MEMBERSHIPS.ROLE, MEMBERSHIPS.ID)
                .from(MEMBERSHIPS).join(ACCOUNTS).on(ACCOUNTS.ID.eq(MEMBERSHIPS.ACCOUNT_ID))
                .where(MEMBERSHIPS.USER_ID.eq(user.id)).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
                .orderBy(ACCOUNTS.NAME)
                .fetch { MyAccount(it[ACCOUNTS.ID], it[ACCOUNTS.NAME], it[MEMBERSHIPS.ROLE], it[ACCOUNTS.STATUS], it[MEMBERSHIPS.ID]) }
        }.filter { principal.tokenMembershipId == null || it.membershipId == principal.tokenMembershipId }
        return MeView(
            id = user.id, email = user.email, name = user.name, locale = user.locale, isInstanceAdmin = user.isInstanceAdmin,
            hasPassword = user.passwordHash != null, emailVerified = user.emailVerifiedAt != null,
            emailVerificationRequired = outbound.verificationRequired, accounts = accounts, currentAccountId = member?.accountId,
            currentMembershipId = member?.membershipId, role = member?.role?.sql,
            permissions = member?.let { Permissions(it.canSeeRates, it.canManageProjects, it.canManageInvoices) },
            twoFactorEnabled = user.totpEnabledAt != null, twoFactorSetupRequired = member?.twoFactorMissing == true,
        )
    }

    @Transactional
    fun update(update: MeUpdate): MeView {
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(Current.principal().userId)).fetchOne()!!
        update.name?.let {
            if (it.isBlank()) throw ValidationException("name", "Enter your name")
            com.honestrobin.time.platform.web.Names.problem(it)?.let { e -> throw ValidationException("name", e) }
            user.name = it.trim()
        }
        update.locale?.let { user.locale = it }
        user.store()
        return me()
    }
}

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "me", description = "The authenticated user")
class MeController(
    private val me: MeService,
    private val auth: AuthService,
    private val tokens: ApiTokenService,
    private val recentAuth: RecentAuth,
) {
    @GetMapping
    fun get() = me.me()

    @PatchMapping
    fun update(@RequestBody body: MeUpdate) = me.update(body)

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@RequestBody body: PasswordChange) {
        val p = Current.principal()
        if (p.isApiToken) throw ForbiddenException("Passwords cannot be changed with an API token")
        auth.changePassword(p.userId, body.currentPassword, body.newPassword, p.sessionId)
    }

    @GetMapping("/api_tokens")
    fun apiTokens(): List<ApiTokenView> = tokens.list(Current.member())

    @PostMapping("/api_tokens")
    @ResponseStatus(HttpStatus.CREATED)
    fun createApiToken(@RequestBody body: NewApiToken): CreatedApiToken {
        if (Current.principal().isApiToken) throw ForbiddenException("API tokens cannot create other API tokens")
        recentAuth.require()
        return tokens.create(Current.member(), body.name, body.scopes.toSet(), body.expiresAt)
    }

    @DeleteMapping("/api_tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeApiToken(@PathVariable id: UUID) = tokens.revoke(Current.member(), id)
}
