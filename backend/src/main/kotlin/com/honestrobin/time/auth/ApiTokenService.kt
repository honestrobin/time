// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.db.Tables.API_TOKENS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.HonestRobinPrincipal
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ApiTokenView(
    val id: UUID,
    val name: String,
    val tokenHint: String,
    val scopes: List<String>,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant,
)

data class CreatedApiToken(val token: String, val apiToken: ApiTokenView)

/** Personal access tokens (`hrt_…`), hashed at rest and bound to a single membership. */
@Service
class ApiTokenService(private val dsl: DSLContext, private val tx: Tx) {
    private val validScopes = setOf("read", "write")

    @Transactional
    fun create(member: Member, name: String, scopes: Set<String>, expiresAt: Instant?): CreatedApiToken {
        if (name.isBlank()) throw ValidationException("name", "Give the token a name")
        if (scopes.isEmpty() || !validScopes.containsAll(scopes)) throw ValidationException("scopes", "Scopes must be read and/or write")
        val token = Tokens.generate(prefix = "hrt_")
        val id = dsl.insertInto(API_TOKENS)
            .set(API_TOKENS.ACCOUNT_ID, member.accountId)
            .set(API_TOKENS.MEMBERSHIP_ID, member.membershipId)
            .set(API_TOKENS.NAME, name.trim())
            .set(API_TOKENS.TOKEN_HASH, Tokens.hash(token))
            .set(API_TOKENS.TOKEN_HINT, token.takeLast(4))
            .set(API_TOKENS.SCOPES, scopes.sorted().toTypedArray())
            .set(API_TOKENS.EXPIRES_AT, expiresAt)
            .returning(API_TOKENS.ID).fetchOne()!!.id
        return CreatedApiToken(token, list(member).first { it.id == id })
    }

    @Transactional(readOnly = true)
    fun list(member: Member): List<ApiTokenView> =
        dsl.selectFrom(API_TOKENS).where(API_TOKENS.MEMBERSHIP_ID.eq(member.membershipId)).orderBy(API_TOKENS.CREATED_AT.desc())
            .fetch { ApiTokenView(it.id, it.name, it.tokenHint, it.scopes.toList(), it.lastUsedAt, it.expiresAt, it.createdAt) }

    @Transactional
    fun revoke(member: Member, id: UUID) {
        val n = dsl.deleteFrom(API_TOKENS).where(API_TOKENS.ID.eq(id)).and(API_TOKENS.MEMBERSHIP_ID.eq(member.membershipId)).execute()
        if (n == 0) throw NotFoundException("API token")
    }

    /** Token lookup is cross-tenant by nature: the token itself identifies the account. */
    fun authenticate(token: String): HonestRobinPrincipal? = tx.system {
        val now = Instant.now()
        val row = dsl.select(API_TOKENS.ID, API_TOKENS.MEMBERSHIP_ID, API_TOKENS.SCOPES, API_TOKENS.LAST_USED_AT, USERS.ID, USERS.EMAIL)
            .from(API_TOKENS)
            .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(API_TOKENS.MEMBERSHIP_ID))
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(API_TOKENS.TOKEN_HASH.eq(Tokens.hash(token)))
            .and(API_TOKENS.EXPIRES_AT.isNull.or(API_TOKENS.EXPIRES_AT.gt(now)))
            .and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
            .fetchOne() ?: return@system null
        val lastUsed = row[API_TOKENS.LAST_USED_AT]
        if (lastUsed == null || Duration.between(lastUsed, now) > Duration.ofMinutes(5)) {
            dsl.update(API_TOKENS).set(API_TOKENS.LAST_USED_AT, now).where(API_TOKENS.ID.eq(row[API_TOKENS.ID])).execute()
        }
        HonestRobinPrincipal(
            userId = row[USERS.ID],
            email = row[USERS.EMAIL],
            apiTokenId = row[API_TOKENS.ID],
            tokenMembershipId = row[API_TOKENS.MEMBERSHIP_ID],
            tokenScopes = row[API_TOKENS.SCOPES].toSet(),
        )
    }
}
