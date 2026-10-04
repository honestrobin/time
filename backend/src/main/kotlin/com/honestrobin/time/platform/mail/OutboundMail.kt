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
 * Limits on email to people outside an account (decision record 0012). They are configuration,
 * not edition. Left unset, they follow the sign-up mode: where anyone can sign up, the numbers
 * Honest Robin Cloud runs with, so a throwaway sign-up can't flood inboxes from the instance's
 * mail domain; elsewhere none, because the owner chose who gets in. 0 means no limit.
 */
@ConfigurationProperties(prefix = "honestrobin.mail.limits")
class MailLimits {
    /** Invitations one account, and one person across their accounts, may send in 24 hours. */
    var invitesPerDay: Int? = null

    /** How soon the same invitation may be sent again. */
    var inviteResendCooldown: Duration? = null

    /** Invoice email recipients (reminders included) per account, and per person sending, in 24 hours. */
    var invoiceRecipientsPerDay: Int? = null

    /** Sign-ups from one IP address in an hour, where anyone can sign up. */
    var signupsPerHourPerIp: Int = 10

    /** The limits in force: the configured ones, or the defaults for this sign-up mode. */
    fun effective(openSignup: Boolean) = Effective(
        invitesPerDay = invitesPerDay ?: if (openSignup) 50 else 0,
        inviteResendCooldown = inviteResendCooldown ?: if (openSignup) Duration.ofMinutes(10) else Duration.ZERO,
        invoiceRecipientsPerDay = invoiceRecipientsPerDay ?: if (openSignup) 300 else 0,
    )

    data class Effective(val invitesPerDay: Int, val inviteResendCooldown: Duration, val invoiceRecipientsPerDay: Int)
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

    private val effective get() = limits.effective(verificationRequired)

    /**
     * Counts [recipients] invoice emails sent by [userId] against the account's daily limit, and
     * against the person's own across all their accounts, so more workspaces don't mean more mail.
     */
    fun checkInvoiceSend(accountId: UUID, userId: UUID, recipients: Int) {
        requireCanSend(accountId)
        val limit = effective.invoiceRecipientsPerDay
        if (limit <= 0) return
        if (!limiter.tryAcquire("invoice-mail:$accountId", limit, Duration.ofDays(1), Instant.now(clock), recipients)) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "invoice_email_limit", "This account has emailed $limit invoice recipients today, the most allowed. Mark the invoice as sent and share its link, or try tomorrow.")
        }
        if (!limiter.tryAcquire("invoice-mail-person:$userId", limit, Duration.ofDays(1), Instant.now(clock), recipients)) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "invoice_email_limit", "You have emailed $limit invoice recipients today, across your workspaces, the most allowed. Mark the invoice as sent and share its link, or try tomorrow.")
        }
    }

    /**
     * Counts a reminder's [recipients] against the account's daily limit. Reminders are invoice
     * emails too: false means the limit is reached, and the reminder waits.
     */
    fun tryReminder(accountId: UUID, recipients: Int): Boolean {
        val limit = effective.invoiceRecipientsPerDay
        return limit <= 0 || limiter.tryAcquire("invoice-mail:$accountId", limit, Duration.ofDays(1), Instant.now(clock), recipients)
    }

    /** Limits sign-ups per IP address where anyone can sign up. */
    fun checkSignup(ip: String?) {
        val limit = limits.signupsPerHourPerIp
        if (!verificationRequired || limit <= 0 || ip == null) return
        if (!limiter.tryAcquire("signup:$ip", limit, Duration.ofHours(1))) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_signups", "Too many sign-ups from this network. Try again in an hour.")
        }
    }

    /**
     * Checks an invitation to [person], sent by [userId], against the confirmation rule, the resend
     * cooldown and the daily limits: the account's, and the person's across all their accounts.
     */
    fun checkInvite(accountId: UUID, userId: UUID, person: MembershipsRecord) {
        requireCanSend(accountId)
        val limits = effective
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
            if (!limiter.tryAcquire("invite-mail-person:$userId", limits.invitesPerDay, Duration.ofDays(1), Instant.now(clock))) {
                throw ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "invite_limit",
                    "You have sent ${limits.invitesPerDay} invitations today, across your workspaces, the most allowed. Try again tomorrow, or write to support if you need more.",
                )
            }
        }
    }
}
