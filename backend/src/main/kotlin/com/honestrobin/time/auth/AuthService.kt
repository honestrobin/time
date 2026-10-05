// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.mail.OutboundMail
import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.accounts.AccountService
import com.honestrobin.time.accounts.NewAccount
import com.honestrobin.time.accounts.SeatTaken
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.API_TOKENS
import com.honestrobin.time.db.Tables.DEVICE_AUTHORIZATIONS
import com.honestrobin.time.db.Tables.LOGIN_TOKENS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.UsersRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.HonestRobinProperties.SignupMode
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.Names
import com.honestrobin.time.platform.web.ValidationException
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class SignupInput(
    val name: String,
    val email: String,
    val password: String,
    val accountName: String,
    val timezone: String? = null,
    val defaultCurrency: String? = null,
    val locale: String? = null,
    val weekStart: Int? = null,
    /** Needed only by the very first sign-up of an instance (see [FirstUserSetup]). */
    val setupCode: String? = null,
)

/**
 * The outcome of a sign-in step: a session, or, with two-factor sign-in on, a [challenge] that
 * waits for the code (and no session yet).
 */
data class SignedIn(val userId: UUID, val sessionToken: String?, val accountId: UUID? = null, val challenge: String? = null)

data class InviteInfo(val email: String, val name: String, val accountName: String, val userExists: Boolean)

enum class TokenPurpose(val sql: String, val ttl: Duration) {
    MAGIC_LINK("magic_link", Duration.ofMinutes(15)),
    PASSWORD_RESET("password_reset", Duration.ofHours(1)),
    INVITE("invite", Duration.ofDays(14)),
    EMAIL_VERIFY("email_verify", Duration.ofDays(7)),
    TWO_FACTOR("two_factor", Duration.ofMinutes(10)),
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@Service
class AuthService(
    private val dsl: DSLContext,
    private val passwords: Passwords,
    private val policy: PasswordPolicy,
    private val sessions: SessionService,
    private val accounts: AccountService,
    private val mailer: Mailer,
    private val throttle: LoginThrottle,
    private val emails: EmailThrottle,
    private val props: HonestRobinProperties,
    private val funnel: Funnel,
    private val outbound: OutboundMail,
    private val recentAuth: RecentAuth,
    private val twoFactor: TwoFactorService,
    private val setup: FirstUserSetup,
    private val notices: SecurityNotices,
    private val tx: com.honestrobin.time.platform.db.Tx,
    private val events: org.springframework.context.ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun hasAnyUser(): Boolean = dsl.fetchExists(USERS)

    @Transactional(readOnly = true)
    fun signupAllowed(): Boolean = when (props.signupMode) {
        SignupMode.OPEN -> true
        SignupMode.FIRST_USER_ONLY -> !hasAnyUser()
        SignupMode.INVITE_ONLY -> false
    }

    /** Creates a user and their first account. The very first user of an instance becomes instance admin. */
    @Transactional
    fun signup(input: SignupInput, ip: String?, userAgent: String?): SignedIn = DbContext.system {
        // Serialise signups so "first user" is decided exactly once.
        dsl.execute("select pg_advisory_xact_lock(7234001)")
        if (!signupAllowed()) throw ApiException(HttpStatus.FORBIDDEN, "signup_closed", "Sign-up is closed on this instance. Ask an admin for an invitation.")
        if (!hasAnyUser() && !setup.matches(input.setupCode)) {
            throw ApiException(
                HttpStatus.FORBIDDEN, "setup_code_required",
                "Enter the setup code from the server's log. Only whoever runs this instance can see it, so nobody else can set it up.",
            )
        }
        outbound.checkSignup(ip)
        val email = normaliseEmail(input.email)
        val errors = buildMap {
            if (input.name.isBlank()) put("name", "Enter your name")
            if (!EMAIL.matches(email)) put("email", "Enter a valid email address")
            policy.problem(input.password, listOf(email, input.name, input.accountName))?.let { put("password", it) }
            if (input.accountName.isBlank()) put("account_name", "Enter a name for your workspace")
            Names.problem(input.name)?.let { put("name", it) }
            Names.problem(input.accountName)?.let { put("account_name", it) }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        if (findUser(email) != null) throw ConflictException("email_taken", "An account with this email already exists. Sign in instead.")

        val firstUser = !hasAnyUser()
        val user = dsl.newRecord(USERS).apply {
            this.email = email
            this.name = input.name.trim()
            this.passwordHash = passwords.hash(input.password)
            this.locale = input.locale ?: "en"
            this.isInstanceAdmin = firstUser
        }
        user.store()
        val accountId = accounts.create(user.id, NewAccount(input.accountName, input.timezone, input.defaultCurrency, input.locale, input.weekStart))
        funnel.event(accountId, Funnel.SIGNUP)
        if (outbound.verificationRequired && emails.allow(user.email)) sendVerification(user)
        SignedIn(user.id, sessions.create(user.id, ip, userAgent), accountId)
    }

    /**
     * Not one transaction: the password check (argon2: slow, and 19 MiB each) runs between two short
     * ones, so a burst of wrong passwords can't hold every database connection while hashing, and
     * each failure is recorded on a connection of its own.
     */
    fun login(email: String, password: String, ip: String?, userAgent: String?): SignedIn {
        val normalised = normaliseEmail(email)
        throttle.check(normalised, ip)
        val user = tx.run { findUser(normalised) }
        if (!passwords.matches(password, user?.passwordHash) || user == null) {
            throttle.failed(normalised, ip)
            throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect")
        }
        return tx.run {
            throttle.succeeded(normalised)
            signIn(user, ip, userAgent)
        }
    }

    /** Always succeeds from the caller's point of view, so it does not reveal which emails exist. */
    @Transactional
    fun requestMagicLink(email: String) {
        val user = findUser(normaliseEmail(email)) ?: return
        if (!emails.allow(user.email)) return
        val token = issueToken(user.id, user.email, TokenPurpose.MAGIC_LINK)
        mailer.send("magic-link", user.email, Locale.forLanguageTag(user.locale), mapOf("name" to user.name, "link" to "${props.baseUrl}/auth/magic#$token"))
    }

    @Transactional
    fun consumeMagicLink(token: String, ip: String?, userAgent: String?): SignedIn {
        val userId = consumeToken(token, TokenPurpose.MAGIC_LINK)
        claimAddress(userId)
        return signIn(userId, ip, userAgent)
    }

    @Transactional
    fun requestPasswordReset(email: String) {
        val user = findUser(normaliseEmail(email)) ?: return
        if (!emails.allow(user.email)) return
        val token = issueToken(user.id, user.email, TokenPurpose.PASSWORD_RESET)
        mailer.send("password-reset", user.email, Locale.forLanguageTag(user.locale), mapOf("name" to user.name, "link" to "${props.baseUrl}/auth/reset#$token"))
    }

    @Transactional
    fun resetPassword(token: String, newPassword: String, ip: String?, userAgent: String?): SignedIn {
        if (newPassword.length !in PasswordPolicy.MIN_LENGTH..PasswordPolicy.MAX_LENGTH) policy.problem(newPassword)?.let { throw ValidationException("password", it) }
        val userId = consumeToken(token, TokenPurpose.PASSWORD_RESET)
        // A refused password rolls back the transaction, so the link still works for another try.
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne()!!
        policy.problem(newPassword, listOf(user.email, user.name))?.let { throw ValidationException("password", it) }
        claimAddress(userId)
        dsl.update(USERS).set(USERS.PASSWORD_HASH, passwords.hash(newPassword)).where(USERS.ID.eq(userId)).execute()
        sessions.revokeAllForUser(userId)
        revokeOutstandingLinks(userId)
        // The emailed link proves the inbox, not the second factor.
        return signIn(userId, ip, userAgent)
    }

    /**
     * Confirms the password again in the current session, for actions that need a recent sign-in
     * (see RecentAuth). Wrong passwords count towards the sign-in limits.
     */
    @Transactional
    fun reauthenticate(userId: UUID, sessionId: UUID, password: String?, code: String?, ip: String?) {
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne()!!
        if (!code.isNullOrBlank()) {
            throttle.check(user.email, ip)
            if (!twoFactor.check(user, code, null)) {
                throttle.failed(user.email, ip)
                throw ApiException(HttpStatus.BAD_REQUEST, "invalid_code", "That code doesn't match. Use the newest code in your authenticator app.")
            }
            throttle.succeeded(user.email)
            sessions.markAuthenticated(sessionId)
            return
        }
        if (user.passwordHash == null) {
            throw ConflictException("no_password", "You sign in with emailed links. Ask for a new sign-in link, open it, then try again.")
        }
        throttle.check(user.email, ip)
        if (password == null || !passwords.matches(password, user.passwordHash)) {
            throttle.failed(user.email, ip)
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_password", "That password is incorrect")
        }
        throttle.succeeded(user.email)
        sessions.markAuthenticated(sessionId)
    }

    @Transactional
    fun changePassword(userId: UUID, currentPassword: String?, newPassword: String, keepSession: UUID?) {
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne()!!
        // Without a current password to ask for, setting the first one needs a recent sign-in.
        if (user.passwordHash == null) recentAuth.require()
        if (user.passwordHash != null && (currentPassword == null || !passwords.matches(currentPassword, user.passwordHash))) {
            throw ValidationException("current_password", "Current password is incorrect")
        }
        policy.problem(newPassword, listOf(user.email, user.name))?.let { throw ValidationException("new_password", it) }
        user.passwordHash = passwords.hash(newPassword)
        user.store()
        sessions.revokeAllForUser(userId, except = keepSession)
        // Sign-in and reset links sent before stop working, and the person hears about the change.
        revokeOutstandingLinks(userId)
        securityNotice(user, "password-changed")
    }

    private fun signIn(userId: UUID, ip: String?, userAgent: String?, membershipId: UUID? = null): SignedIn =
        signIn(dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne()!!, ip, userAgent, membershipId)

    /** A session, or with two-factor sign-in on, a challenge for the code. */
    private fun signIn(user: UsersRecord, ip: String?, userAgent: String?, membershipId: UUID? = null): SignedIn {
        val accountId = membershipId?.let { dsl.select(MEMBERSHIPS.ACCOUNT_ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(it)).fetchOne()?.value1() }
        if (twoFactor.enabled(user)) {
            return SignedIn(user.id, null, accountId, challenge = issueToken(user.id, user.email, TokenPurpose.TWO_FACTOR, membershipId))
        }
        return SignedIn(user.id, sessions.create(user.id, ip, userAgent), accountId)
    }

    /**
     * The second step of signing in: a code from the authenticator app, or a recovery code, for
     * the challenge the first step returned. Wrong codes count towards the sign-in limits.
     */
    @Transactional
    fun completeSecondFactor(challenge: String, code: String?, recoveryCode: String?, ip: String?, userAgent: String?): SignedIn = DbContext.system {
        val row = dsl.selectFrom(LOGIN_TOKENS)
            .where(LOGIN_TOKENS.TOKEN_HASH.eq(Tokens.hash(challenge)))
            .and(LOGIN_TOKENS.PURPOSE.eq(TokenPurpose.TWO_FACTOR.sql))
            .and(LOGIN_TOKENS.USED_AT.isNull)
            .and(LOGIN_TOKENS.EXPIRES_AT.gt(Instant.now()))
            .fetchOne() ?: throw ApiException(HttpStatus.BAD_REQUEST, "challenge_expired", "That took too long. Sign in again.")
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(row.userId)).fetchOne()!!
        throttle.checkSecondFactor(user.id, row.id, ip)
        if (!twoFactor.check(user, code, recoveryCode)) {
            val failures = throttle.failedSecondFactor(user.id, row.id, ip)
            // Whoever got this far knows the password: tell its owner, once.
            if (failures == LoginThrottle.SECOND_FACTOR_NOTICE_AT) securityNotice(user, "code-failures", failures, evenIfRolledBack = true)
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_code", "That code doesn't match. Use the newest code in your authenticator app.")
        }
        throttle.succeededSecondFactor(user.id)
        row.usedAt = Instant.now()
        row.store()
        val accountId = row.membershipId?.let { dsl.select(MEMBERSHIPS.ACCOUNT_ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(it)).fetchOne()?.value1() }
        SignedIn(user.id, sessions.create(user.id, ip, userAgent), accountId)
    }

    /** Shows who is invited to what, without consuming the token. */
    @Transactional(readOnly = true)
    fun lookupInvite(token: String): InviteInfo = DbContext.system {
        val row = inviteRow(token)
        InviteInfo(row[MEMBERSHIPS.EMAIL], row[MEMBERSHIPS.NAME], row[ACCOUNTS.NAME], findUser(row[MEMBERSHIPS.EMAIL]) != null)
    }

    /**
     * Accepts an invitation. The token proves control of the invited email address, so an existing
     * user with that email is linked; otherwise a user is created (password optional: magic links work too).
     */
    @Transactional
    fun acceptInvite(token: String, name: String?, password: String?, ip: String?, userAgent: String?): SignedIn = DbContext.system {
        val row = inviteRow(token)
        val membershipId = row[MEMBERSHIPS.ID]
        dsl.update(LOGIN_TOKENS).set(LOGIN_TOKENS.USED_AT, Instant.now()).where(LOGIN_TOKENS.TOKEN_HASH.eq(Tokens.hash(token))).execute()
        val email = row[MEMBERSHIPS.EMAIL]
        // An existing user is signed in as by this link the way a sign-in link would; if their
        // address wasn't confirmed, it is claimed first (see claimAddress).
        val user = findUser(email)?.also { claimAddress(it.id) } ?: run {
            val display = name?.trim().takeUnless { it.isNullOrBlank() } ?: row[MEMBERSHIPS.NAME]
            if (password != null) policy.problem(password, listOf(email, display, row[ACCOUNTS.NAME]))?.let { throw ValidationException("password", it) }
            dsl.newRecord(USERS).apply {
                this.email = normaliseEmail(email)
                this.name = display
                this.passwordHash = password?.let(passwords::hash)
                this.emailVerifiedAt = Instant.now()
            }.also { it.store() }
        }
        if (dsl.fetchExists(MEMBERSHIPS, MEMBERSHIPS.ACCOUNT_ID.eq(row[MEMBERSHIPS.ACCOUNT_ID]).and(MEMBERSHIPS.USER_ID.eq(user.id)).and(MEMBERSHIPS.ID.ne(membershipId)))) {
            throw ConflictException("already_member", "You are already a member of this account")
        }
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.USER_ID, user.id)
            .set(MEMBERSHIPS.STATUS, "active")
            .set(MEMBERSHIPS.NAME, user.name)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .execute()
        // They can sign in from now on, so this is when their seat starts to count, not the invitation.
        events.publishEvent(SeatTaken(row[MEMBERSHIPS.ACCOUNT_ID]))
        markEmailVerified(user.id)
        signIn(user.id, ip, userAgent, membershipId)
    }

    private fun inviteRow(token: String) =
        dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.EMAIL, MEMBERSHIPS.NAME, MEMBERSHIPS.ACCOUNT_ID, ACCOUNTS.NAME)
            .from(LOGIN_TOKENS)
            .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(LOGIN_TOKENS.MEMBERSHIP_ID))
            .join(ACCOUNTS).on(ACCOUNTS.ID.eq(MEMBERSHIPS.ACCOUNT_ID))
            .where(LOGIN_TOKENS.TOKEN_HASH.eq(Tokens.hash(token)))
            .and(LOGIN_TOKENS.PURPOSE.eq(TokenPurpose.INVITE.sql))
            // The invitation is for the address it was sent to: if the person's email changed
            // since, the old link must not sign anyone in under the new address.
            .and(DSL.lower(LOGIN_TOKENS.EMAIL).eq(DSL.lower(MEMBERSHIPS.EMAIL)))
            .and(LOGIN_TOKENS.USED_AT.isNull)
            .and(LOGIN_TOKENS.EXPIRES_AT.gt(Instant.now()))
            .and(MEMBERSHIPS.IS_ACTIVE.isTrue)
            .and(MEMBERSHIPS.STATUS.ne("active"))
            .fetchOne() ?: throw ApiException(HttpStatus.BAD_REQUEST, "invalid_link", "This invitation is invalid or has expired. Ask for a new one.")

    fun issueToken(userId: UUID?, email: String, purpose: TokenPurpose, membershipId: UUID? = null): String {
        val token = Tokens.generate()
        // A new sign-in or reset link replaces the earlier ones: only the latest works.
        if (userId != null && purpose in setOf(TokenPurpose.MAGIC_LINK, TokenPurpose.PASSWORD_RESET)) {
            dsl.update(LOGIN_TOKENS).set(LOGIN_TOKENS.USED_AT, Instant.now())
                .where(LOGIN_TOKENS.USER_ID.eq(userId)).and(LOGIN_TOKENS.PURPOSE.eq(purpose.sql)).and(LOGIN_TOKENS.USED_AT.isNull).execute()
        }
        // So is a new invitation: an older one, maybe forwarded or left in an inbox, stops working.
        if (purpose == TokenPurpose.INVITE && membershipId != null) {
            dsl.update(LOGIN_TOKENS).set(LOGIN_TOKENS.USED_AT, Instant.now())
                .where(LOGIN_TOKENS.MEMBERSHIP_ID.eq(membershipId)).and(LOGIN_TOKENS.PURPOSE.eq(purpose.sql)).and(LOGIN_TOKENS.USED_AT.isNull).execute()
        }
        dsl.insertInto(LOGIN_TOKENS)
            .set(LOGIN_TOKENS.USER_ID, userId)
            .set(LOGIN_TOKENS.MEMBERSHIP_ID, membershipId)
            .set(LOGIN_TOKENS.EMAIL, email)
            .set(LOGIN_TOKENS.PURPOSE, purpose.sql)
            .set(LOGIN_TOKENS.TOKEN_HASH, Tokens.hash(token))
            .set(LOGIN_TOKENS.EXPIRES_AT, Instant.now().plus(purpose.ttl))
            .execute()
        return token
    }

    private fun consumeToken(token: String, purpose: TokenPurpose): UUID {
        val row = dsl.update(LOGIN_TOKENS)
            .set(LOGIN_TOKENS.USED_AT, Instant.now())
            .where(LOGIN_TOKENS.TOKEN_HASH.eq(Tokens.hash(token)))
            .and(LOGIN_TOKENS.PURPOSE.eq(purpose.sql))
            .and(LOGIN_TOKENS.USED_AT.isNull)
            .and(LOGIN_TOKENS.EXPIRES_AT.gt(Instant.now()))
            .returning(LOGIN_TOKENS.USER_ID)
            .fetchOne()
        return row?.userId ?: throw ApiException(HttpStatus.BAD_REQUEST, "invalid_link", "This link is invalid or has expired. Request a new one.")
    }

    /** Asks a new user to confirm their address: until an admin has, the account emails nobody else. */
    private fun sendVerification(user: UsersRecord) {
        val token = issueToken(user.id, user.email, TokenPurpose.EMAIL_VERIFY)
        mailer.send("verify-email", user.email, Locale.forLanguageTag(user.locale), mapOf("name" to user.name, "link" to "${props.baseUrl}/auth/verify#$token"))
    }

    /**
     * Confirms the address from the sign-up link. The person must be signed in as the user who
     * signed up: otherwise whoever registered someone else's address could get its owner to
     * "confirm" it for them by clicking the link.
     */
    @Transactional
    fun verifyEmail(token: String, signedInUser: UUID?) = DbContext.system {
        if (signedInUser == null) throw ApiException(HttpStatus.UNAUTHORIZED, "sign_in_to_confirm", "Sign in, then open the link again to confirm your address.")
        val owner = dsl.select(LOGIN_TOKENS.USER_ID).from(LOGIN_TOKENS).where(LOGIN_TOKENS.TOKEN_HASH.eq(Tokens.hash(token)))
            .and(LOGIN_TOKENS.PURPOSE.eq(TokenPurpose.EMAIL_VERIFY.sql)).fetchOne()?.value1()
        if (owner != null && owner != signedInUser) throw ApiException(HttpStatus.FORBIDDEN, "wrong_user", "This link confirms another address. Sign in as that user to confirm it.")
        markEmailVerified(consumeToken(token, TokenPurpose.EMAIL_VERIFY))
    }

    /**
     * A link sent to the address proves its owner is here. If the address wasn't confirmed until
     * now, whoever registered it may not be its owner (open sign-up lets anyone type any
     * address), so their password, sessions, API tokens and other links go: the person who
     * proved the inbox starts clean, and sets a password if they want one.
     */
    private fun claimAddress(userId: UUID) {
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne() ?: return
        if (user.emailVerifiedAt != null) return
        // Only open sign-up lets someone register an address that isn't theirs. Anywhere else the
        // only unconfirmed address is the first user's, typed by the instance's owner: proving the
        // inbox confirms it, and their password and two-factor sign-in stay.
        if (props.signupMode != SignupMode.OPEN) {
            markEmailVerified(userId)
            return
        }
        user.passwordHash = null
        user.emailVerifiedAt = Instant.now()
        user.store()
        // Whoever registered the address may have set up two-factor sign-in to lock its owner out.
        twoFactor.clear(user)
        sessions.revokeAllForUser(userId)
        // API tokens and device sign-ins hang off memberships, which row-level security hides from
        // a request that has no account yet: without the system context nothing would be removed.
        DbContext.system {
            val memberships = DSL.select(MEMBERSHIPS.ID).from(MEMBERSHIPS).where(MEMBERSHIPS.USER_ID.eq(userId))
            dsl.deleteFrom(API_TOKENS).where(API_TOKENS.MEMBERSHIP_ID.`in`(memberships)).execute()
            dsl.update(DEVICE_AUTHORIZATIONS).set(DEVICE_AUTHORIZATIONS.STATUS, "denied")
                .where(DEVICE_AUTHORIZATIONS.STATUS.`in`("pending", "approved")).and(DEVICE_AUTHORIZATIONS.MEMBERSHIP_ID.`in`(memberships)).execute()
        }
        revokeOutstandingLinks(userId)
        securityNotice(user, "address-claimed")
    }

    private fun securityNotice(user: UsersRecord, event: String, arg: Any? = null, evenIfRolledBack: Boolean = false) =
        notices.send(user, event, arg, evenIfRolledBack)

    /** Sign-in, reset, invitation and confirmation links, once used or expired for 30 days, go. */
    @Transactional
    fun purgeOldLinks(): Int {
        val cutoff = Instant.now().minus(Duration.ofDays(30))
        return dsl.deleteFrom(LOGIN_TOKENS).where(LOGIN_TOKENS.USED_AT.lt(cutoff)).or(LOGIN_TOKENS.EXPIRES_AT.lt(cutoff)).execute()
    }

    private fun revokeOutstandingLinks(userId: UUID) {
        dsl.update(LOGIN_TOKENS).set(LOGIN_TOKENS.USED_AT, Instant.now())
            .where(LOGIN_TOKENS.USER_ID.eq(userId)).and(LOGIN_TOKENS.USED_AT.isNull).execute()
    }

    /** Sends the confirmation link again (limited like sign-in emails). */
    @Transactional
    fun resendVerification(userId: UUID) = DbContext.system {
        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne() ?: return@system
        if (user.emailVerifiedAt != null || !emails.allow(user.email)) return@system
        sendVerification(user)
    }

    private fun markEmailVerified(userId: UUID) {
        dsl.update(USERS).set(USERS.EMAIL_VERIFIED_AT, DSL.coalesce(USERS.EMAIL_VERIFIED_AT, DSL.currentInstant()))
            .where(USERS.ID.eq(userId)).execute()
    }

    fun findUser(email: String): UsersRecord? =
        dsl.selectFrom(USERS).where(DSL.lower(USERS.EMAIL).eq(email.lowercase())).fetchOne()

    companion object {
        fun normaliseEmail(email: String) = email.trim().lowercase()
    }
}
