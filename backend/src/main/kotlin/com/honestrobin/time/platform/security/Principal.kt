// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.security

import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ForbiddenException
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import java.time.Instant
import java.util.UUID

enum class Role(val sql: String) {
    ADMIN("admin"),
    MANAGER("manager"),
    MEMBER("member"),
    ;

    companion object {
        fun of(sql: String) = entries.first { it.sql == sql }
    }
}

/** An authenticated user, via a browser session or an API token. */
data class HonestRobinPrincipal(
    val userId: UUID,
    val email: String,
    val sessionId: UUID? = null,
    val apiTokenId: UUID? = null,
    /** API tokens are bound to one membership. */
    val tokenMembershipId: UUID? = null,
    val tokenScopes: Set<String> = emptySet(),
    /** Sessions: when the person last signed in or confirmed their password. */
    val authenticatedAt: Instant? = null,
) {
    val isApiToken get() = apiTokenId != null
}

class HonestRobinAuthentication(val principal: HonestRobinPrincipal) : AbstractAuthenticationToken(AuthorityUtils.NO_AUTHORITIES) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = principal
}

/**
 * The caller's membership in the account addressed by the request. Permission flags
 * follow the matrix in spec §11; configurable ones live on the membership row.
 */
data class Member(
    val membershipId: UUID,
    val accountId: UUID,
    val userId: UUID,
    val role: Role,
    val name: String,
    val canSeeRates: Boolean,
    val canManageProjects: Boolean,
    val canManageInvoices: Boolean,
    val accountStatus: String,
    /** The account requires two-factor sign-in and this person hasn't set it up. */
    val twoFactorMissing: Boolean = false,
) {
    val isAdmin get() = role == Role.ADMIN
    val isManagerOrAdmin get() = role != Role.MEMBER

    /** Lapsed accounts are read-only (export still works, spec §1.2.2). */
    val isReadOnly get() = accountStatus != "active"

    fun requireAdmin() {
        if (!isAdmin) throw ForbiddenException("Only account admins can do this")
    }

    fun requireManagerOrAdmin() {
        if (!isManagerOrAdmin) throw ForbiddenException()
    }

    fun requireWritable() {
        if (isReadOnly) throw ApiException(HttpStatus.PAYMENT_REQUIRED, "account_read_only", "This account is read-only. Export remains available.")
    }
}

/** Per-request holder for the authenticated principal and resolved membership. */
object Current {
    private val principal = ThreadLocal<HonestRobinPrincipal?>()
    private val member = ThreadLocal<Member?>()

    fun set(p: HonestRobinPrincipal?, m: Member?) {
        principal.set(p)
        member.set(m)
    }

    fun clear() {
        principal.remove()
        member.remove()
    }

    fun principalOrNull(): HonestRobinPrincipal? = principal.get()

    fun principal(): HonestRobinPrincipal = principal.get() ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Please sign in")

    fun memberOrNull(): Member? = member.get()

    fun member(): Member = member.get() ?: throw ApiException(
        HttpStatus.BAD_REQUEST,
        "account_required",
        "Select an account with the HonestRobin-Account-Id header",
    )

    fun <T> withMember(m: Member?, block: () -> T): T {
        val prev = member.get()
        member.set(m)
        try {
            return block()
        } finally {
            member.set(prev)
        }
    }
}
