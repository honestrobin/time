// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.Tables.USER_SESSIONS
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.security.HonestRobinPrincipal
import org.jooq.DSLContext
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class SessionService(private val dsl: DSLContext, private val props: HonestRobinProperties) {

    @Transactional
    fun create(userId: UUID, ip: String?, userAgent: String?): String {
        val token = Tokens.generate()
        dsl.insertInto(USER_SESSIONS)
            .set(USER_SESSIONS.USER_ID, userId)
            .set(USER_SESSIONS.TOKEN_HASH, Tokens.hash(token))
            .set(USER_SESSIONS.IP, ip)
            .set(USER_SESSIONS.USER_AGENT, userAgent?.take(500))
            .set(USER_SESSIONS.EXPIRES_AT, Instant.now().plus(props.session.ttl))
            .set(USER_SESSIONS.AUTHENTICATED_AT, Instant.now())
            .execute()
        dsl.update(USERS).set(USERS.LAST_LOGIN_AT, Instant.now()).where(USERS.ID.eq(userId)).execute()
        return token
    }

    @Transactional
    fun resolve(token: String): HonestRobinPrincipal? {
        val now = Instant.now()
        val row = dsl.select(USER_SESSIONS.ID, USER_SESSIONS.LAST_SEEN_AT, USER_SESSIONS.AUTHENTICATED_AT, USERS.ID, USERS.EMAIL)
            .from(USER_SESSIONS).join(USERS).on(USERS.ID.eq(USER_SESSIONS.USER_ID))
            .where(USER_SESSIONS.TOKEN_HASH.eq(Tokens.hash(token)))
            .and(USER_SESSIONS.EXPIRES_AT.gt(now))
            .and(USER_SESSIONS.CREATED_AT.gt(now.minus(props.session.maxLifetime)))
            .fetchOne() ?: return null
        // Sliding expiry, written at most every 10 minutes.
        if (Duration.between(row[USER_SESSIONS.LAST_SEEN_AT], now) > Duration.ofMinutes(10)) {
            dsl.update(USER_SESSIONS)
                .set(USER_SESSIONS.LAST_SEEN_AT, now)
                .set(USER_SESSIONS.EXPIRES_AT, now.plus(props.session.ttl))
                .where(USER_SESSIONS.ID.eq(row[USER_SESSIONS.ID]))
                .execute()
        }
        return HonestRobinPrincipal(
            userId = row[USERS.ID], email = row[USERS.EMAIL], sessionId = row[USER_SESSIONS.ID], authenticatedAt = row[USER_SESSIONS.AUTHENTICATED_AT],
        )
    }

    /** The person just proved who they are again (see RecentAuth). */
    @Transactional
    fun markAuthenticated(sessionId: UUID) {
        dsl.update(USER_SESSIONS).set(USER_SESSIONS.AUTHENTICATED_AT, Instant.now()).where(USER_SESSIONS.ID.eq(sessionId)).execute()
    }

    @Transactional
    fun revoke(sessionId: UUID) {
        dsl.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.ID.eq(sessionId)).execute()
    }

    @Transactional
    fun revokeAllForUser(userId: UUID, except: UUID? = null) {
        dsl.deleteFrom(USER_SESSIONS)
            .where(USER_SESSIONS.USER_ID.eq(userId))
            .apply { if (except != null) and(USER_SESSIONS.ID.ne(except)) }
            .execute()
    }

    @Transactional
    fun purgeExpired(): Int = dsl.deleteFrom(USER_SESSIONS)
        .where(USER_SESSIONS.EXPIRES_AT.lt(Instant.now()))
        .or(USER_SESSIONS.CREATED_AT.lt(Instant.now().minus(props.session.maxLifetime)))
        .execute()

    fun cookie(token: String): ResponseCookie = ResponseCookie.from(props.session.cookieName, token)
        .httpOnly(true).secure(props.secureCookies).sameSite("Lax").path("/").maxAge(props.session.maxLifetime).build()

    fun clearCookie(): ResponseCookie = ResponseCookie.from(props.session.cookieName, "")
        .httpOnly(true).secure(props.secureCookies).sameSite("Lax").path("/").maxAge(0).build()
}
