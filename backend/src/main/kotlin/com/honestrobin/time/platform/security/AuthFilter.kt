// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.security

import com.honestrobin.time.accounts.MembershipResolver
import com.honestrobin.time.auth.ApiTokenService
import com.honestrobin.time.auth.SessionService
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.db.ActorType
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.web.ApiError
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

const val ACCOUNT_HEADER = "HonestRobin-Account-Id"

/**
 * Authenticates by session cookie or `Authorization: Bearer <api token>`, resolves the
 * addressed account membership, and establishes the database context for the request.
 */
@Component
class AuthFilter(
    private val sessions: SessionService,
    private val apiTokens: ApiTokenService,
    private val memberships: MembershipResolver,
    private val props: HonestRobinProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return !(path.startsWith("/api/") || path.startsWith("/v3/"))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            val ip = request.remoteAddr
            val bearer = request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
            val principal = when {
                bearer != null -> apiTokens.authenticate(bearer) ?: return reject(response, HttpStatus.UNAUTHORIZED, "invalid_token", "API token is invalid or expired")
                else -> sessionCookie(request)?.let { sessions.resolve(it) }
            }

            if (principal?.isApiToken == true && "write" !in principal.tokenScopes && request.method !in setOf("GET", "HEAD", "OPTIONS")) {
                return reject(response, HttpStatus.FORBIDDEN, "insufficient_scope", "This API token is read-only")
            }

            var member: Member? = null
            if (principal != null) {
                val requested = request.getHeader(ACCOUNT_HEADER)?.let {
                    runCatching { UUID.fromString(it) }.getOrNull() ?: return reject(response, HttpStatus.BAD_REQUEST, "bad_account", "Malformed $ACCOUNT_HEADER header")
                }
                member = if (principal.tokenMembershipId != null) {
                    memberships.byMembershipId(principal.tokenMembershipId)?.takeIf { requested == null || requested == it.accountId }
                        ?: return reject(response, HttpStatus.FORBIDDEN, "not_a_member", "This token cannot access that account")
                } else {
                    memberships.resolve(principal.userId, requested)
                        ?: if (requested != null) return reject(response, HttpStatus.FORBIDDEN, "not_a_member", "You are not a member of that account") else null
                }
                SecurityContextHolder.getContext().authentication = HonestRobinAuthentication(principal)
            }

            if (member?.twoFactorMissing == true && !allowedWithoutTwoFactor(request)) {
                return reject(response, HttpStatus.FORBIDDEN, "two_factor_required", "This account requires two-factor sign-in. Set it up under your profile to continue.")
            }

            Current.set(principal, member)
            DbContext.set(
                DbContext(
                    accountId = member?.accountId,
                    actorUserId = principal?.userId,
                    actorType = if (principal?.isApiToken == true) ActorType.API_TOKEN else if (principal != null) ActorType.USER else ActorType.SYSTEM,
                    ip = ip,
                ),
            )
            chain.doFilter(request, response)
        } finally {
            Current.clear()
            DbContext.set(null)
        }
    }

    /** Until two-factor sign-in is set up, only what it takes to set it up (or leave). */
    private fun allowedWithoutTwoFactor(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        val get = request.method == "GET"
        return path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/me/two_factor") || path.startsWith("/v3/") ||
            (get && (path == "/api/v1/me" || path == "/api/v1/account"))
    }

    private fun sessionCookie(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == props.session.cookieName }?.value?.takeIf { it.isNotBlank() }

    private fun reject(response: HttpServletResponse, status: HttpStatus, code: String, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, ApiError(code, message))
    }
}
