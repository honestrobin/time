// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.mail

import com.honestrobin.time.db.Tables.LOGIN_TOKENS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.MembershipsRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.security.RateLimiter
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ConflictException
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Limits on invitation emails (decision record 0012). They are configuration, not edition: a
 * self-hosted instance sends as many as its owner likes (0 = no limit, the default), and Honest
 * Robin Cloud sets them so nobody can use our mail domain to flood inboxes.
 */
@ConfigurationProperties(prefix = "honestrobin.mail.limits")
class MailLimits {
    /** Invitations one account may send in 24 hours; 0 means no limit. */
    var invitesPerDay: Int = 0

    /** How soon the same invitation may be sent again. */
    var inviteResendCooldown: Duration = Duration.ZERO

    /** Invoice email recipients one account may send to in 24 hours; 0 means no limit. */
    var invoiceRecipientsPerDay: Int = 0

    /** Sign-ups from one IP address in an hour, where anyone can sign up; 0 means no limit. */
    var signupsPerHourPerIp: Int = 10
}

/**
 * Whether an account may email people outside it: invitations, invoices, reminders. Where anyone
 * can sign up, that waits until one of the account's admins has confirmed their own email address,
 * so a throwaway sign-up can't send mail in our name.
 */
@Component
class OutboundMail(
    private val dsl: DSLContext,
    private val props: HonestRobinProperties,
    private val limits: MailLimits,
    private val clock: Clock,
    private val limiter: RateLimiter,
) {
    val verificationRequired: Boolean get() = props.signupMode == HonestRobinProperties.SignupMode.OPEN

    fun canSend(accountId: UUID): Boolean = !verificationRequired || dsl.fetchExists(
        MEMBERSHIPS.join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID)),
        MEMBERSHIPS.ACCOUNT_ID.eq(accountId).and(MEMBERSHIPS.ROLE.eq("admin")).and(MEMBERSHIPS.IS_ACTIVE.isTrue)
            .and(USERS.EMAIL_VERIFIED_AT.isNotNull),
    )

    fun requireCanSend(accountId: UUID) {
        if (!canSend(accountId)) {
            throw ConflictException("email_unverified", "Confirm your email address first: we sent you a link. Until then this account can't email anyone.")
        }
    }

    /** Counts [recipients] invoice emails against the account's daily limit. */
    fun checkInvoiceSend(accountId: UUID, recipients: Int) {
        requireCanSend(accountId)
        val limit = limits.invoiceRecipientsPerDay
        if (limit <= 0) return
        if (!limiter.tryAcquire("invoice-mail:$accountId", limit, Duration.ofDays(1), Instant.now(clock), recipients)) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "invoice_email_limit", "This account has emailed $limit invoice recipients today, the most allowed. Mark the invoice as sent and share its link, or try tomorrow.")
        }
    }

    /** Limits sign-ups per IP address where anyone can sign up. */
    fun checkSignup(ip: String?) {
        val limit = limits.signupsPerHourPerIp
        if (!verificationRequired || limit <= 0 || ip == null) return
        if (!limiter.tryAcquire("signup:$ip", limit, Duration.ofHours(1))) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_signups", "Too many sign-ups from this network. Try again in an hour.")
        }
    }

    /** Checks an invitation to [person] against the confirmation rule, the resend cooldown and the daily limit. */
    fun checkInvite(accountId: UUID, person: MembershipsRecord) {
        requireCanSend(accountId)
        val cooldown = limits.inviteResendCooldown
        if (!cooldown.isZero && person.invitedAt?.isAfter(Instant.now(clock).minus(cooldown)) == true) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "invite_cooldown", "This invitation was sent a moment ago. Try again in a few minutes.")
        }
        if (limits.invitesPerDay > 0) {
            val sent = dsl.fetchCount(
                LOGIN_TOKENS,
                LOGIN_TOKENS.PURPOSE.eq("invite").and(LOGIN_TOKENS.CREATED_AT.gt(Instant.now().minus(Duration.ofDays(1))))
                    .and(LOGIN_TOKENS.MEMBERSHIP_ID.`in`(DSL.select(MEMBERSHIPS.ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ACCOUNT_ID.eq(accountId)))),
            )
            if (sent >= limits.invitesPerDay) {
                throw ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "invite_limit",
                    "This account has sent ${limits.invitesPerDay} invitations today, the most allowed. Try again tomorrow, or write to support if you need more.",
                )
            }
        }
    }
}
