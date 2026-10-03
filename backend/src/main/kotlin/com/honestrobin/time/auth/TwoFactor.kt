// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.Tables.USER_RECOVERY_CODES
import com.honestrobin.time.db.tables.records.UsersRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.crypto.SecretBox
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.crypto.Totp
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ValidationException
import io.nayuki.qrcodegen.QrCode
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class TwoFactorStatus(
    val enabled: Boolean,
    val enabledAt: Instant?,
    val recoveryCodesLeft: Int,
    /** Names of the accounts that require two-factor sign-in of their members. */
    val requiredBy: List<String>,
)

data class TwoFactorSetup(
    /** For typing into the app by hand, in groups of four. */
    val secret: String,
    val otpauthUri: String,
    /** The same link as a QR code, as SVG. */
    val qrSvg: String,
)

data class RecoveryCodes(val recoveryCodes: List<String>)

data class TwoFactorCode(val code: String)

/**
 * Two-factor sign-in with an authenticator app (TOTP, RFC 6238), plus ten one-time recovery codes
 * for when the phone is gone. Every change is confirmed by email to the person.
 */
@Service
class TwoFactorService(
    private val dsl: DSLContext,
    private val box: SecretBox,
    private val recentAuth: RecentAuth,
    private val mailer: Mailer,
    private val props: HonestRobinProperties,
) {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun status(userId: UUID): TwoFactorStatus = DbContext.system {
        val user = user(userId)
        val left = dsl.fetchCount(USER_RECOVERY_CODES, USER_RECOVERY_CODES.USER_ID.eq(userId).and(USER_RECOVERY_CODES.USED_AT.isNull))
        TwoFactorStatus(user.totpEnabledAt != null, user.totpEnabledAt, left, requiredBy(userId))
    }

    /** Starts setup: a new secret, kept aside until a first code shows the app has it. */
    @Transactional
    fun setup(userId: UUID): TwoFactorSetup = DbContext.system {
        recentAuth.require()
        val user = user(userId)
        if (user.totpEnabledAt != null) throw ConflictException("already_enabled", "Two-factor sign-in is already on. Turn it off first to move it to another app.")
        val secret = Totp.newSecret()
        user.totpPendingEncrypted = box.encrypt(secret)
        user.store()
        val uri = Totp.uri(secret, user.email, issuer())
        TwoFactorSetup(secret.chunked(4).joinToString(" "), uri, svg(QrCode.encodeText(uri, QrCode.Ecc.MEDIUM)))
    }

    /** Finishes setup with a code from the app, and hands out the recovery codes (shown once). */
    @Transactional
    fun enable(userId: UUID, code: String): RecoveryCodes = DbContext.system {
        val user = user(userId)
        if (user.totpEnabledAt != null) throw ConflictException("already_enabled", "Two-factor sign-in is already on.")
        val pending = user.totpPendingEncrypted?.let(box::decrypt)
            ?: throw ConflictException("no_setup", "Start the setup again: scan the new QR code, then enter the code.")
        val step = Totp.verify(pending, code, Instant.now(), null) ?: throw wrongCode()
        user.totpSecretEncrypted = user.totpPendingEncrypted
        user.totpPendingEncrypted = null
        user.totpEnabledAt = Instant.now()
        user.totpLastStep = step
        user.store()
        notify(user, "two-factor-on")
        RecoveryCodes(newRecoveryCodes(userId))
    }

    @Transactional
    fun disable(userId: UUID) = DbContext.system {
        recentAuth.require()
        val user = user(userId)
        if (user.totpEnabledAt == null) return@system
        requiredBy(userId).firstOrNull()?.let {
            throw ConflictException("required_by_account", "$it requires two-factor sign-in of everyone, so it stays on.")
        }
        clear(user)
        notify(user, "two-factor-off")
    }

    @Transactional
    fun regenerateRecoveryCodes(userId: UUID): RecoveryCodes = DbContext.system {
        recentAuth.require()
        val user = user(userId)
        if (user.totpEnabledAt == null) throw ConflictException("not_enabled", "Turn on two-factor sign-in first.")
        notify(user, "recovery-new")
        RecoveryCodes(newRecoveryCodes(userId))
    }

    fun enabled(user: UsersRecord) = user.totpEnabledAt != null && user.totpSecretEncrypted != null

    /**
     * Checks a code from the app, or a recovery code, for [user]. Each works once: an app code's
     * time step is remembered, a recovery code is marked used.
     */
    fun check(user: UsersRecord, code: String?, recoveryCode: String?): Boolean {
        if (!enabled(user)) return false
        if (!code.isNullOrBlank()) {
            val step = Totp.verify(box.decrypt(user.totpSecretEncrypted!!), code, Instant.now(), user.totpLastStep) ?: return false
            // Conditional, so two requests racing with the same code can't both succeed.
            return dsl.update(USERS).set(USERS.TOTP_LAST_STEP, step)
                .where(USERS.ID.eq(user.id)).and(USERS.TOTP_LAST_STEP.isNull.or(USERS.TOTP_LAST_STEP.lt(step)))
                .execute() == 1
        }
        if (!recoveryCode.isNullOrBlank()) {
            val used = dsl.update(USER_RECOVERY_CODES).set(USER_RECOVERY_CODES.USED_AT, Instant.now())
                .where(USER_RECOVERY_CODES.USER_ID.eq(user.id))
                .and(USER_RECOVERY_CODES.CODE_HASH.eq(Tokens.hash(normalise(recoveryCode))))
                .and(USER_RECOVERY_CODES.USED_AT.isNull)
                .execute() == 1
            if (used) {
                val left = dsl.fetchCount(USER_RECOVERY_CODES, USER_RECOVERY_CODES.USER_ID.eq(user.id).and(USER_RECOVERY_CODES.USED_AT.isNull))
                notify(user, "recovery-used", left)
            }
            return used
        }
        return false
    }

    /** Removes two-factor sign-in without notice: used when an unconfirmed address is claimed by its owner. */
    fun clear(user: UsersRecord) {
        user.totpSecretEncrypted = null
        user.totpPendingEncrypted = null
        user.totpEnabledAt = null
        user.totpLastStep = null
        user.store()
        dsl.deleteFrom(USER_RECOVERY_CODES).where(USER_RECOVERY_CODES.USER_ID.eq(user.id)).execute()
    }

    /** Accounts [userId] belongs to that require two-factor sign-in. */
    fun requiredBy(userId: UUID): List<String> =
        dsl.select(ACCOUNTS.NAME).from(MEMBERSHIPS).join(ACCOUNTS).on(ACCOUNTS.ID.eq(MEMBERSHIPS.ACCOUNT_ID))
            .where(MEMBERSHIPS.USER_ID.eq(userId)).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
            .and(ACCOUNTS.REQUIRE_TWO_FACTOR.isTrue)
            .orderBy(ACCOUNTS.NAME)
            .fetch(ACCOUNTS.NAME)

    private fun newRecoveryCodes(userId: UUID): List<String> {
        dsl.deleteFrom(USER_RECOVERY_CODES).where(USER_RECOVERY_CODES.USER_ID.eq(userId)).execute()
        val codes = List(RECOVERY_CODES) {
            val raw = (1..10).map { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }.joinToString("")
            "${raw.take(5)}-${raw.drop(5)}"
        }
        codes.forEach { c ->
            dsl.insertInto(USER_RECOVERY_CODES).set(USER_RECOVERY_CODES.USER_ID, userId).set(USER_RECOVERY_CODES.CODE_HASH, Tokens.hash(normalise(c))).execute()
        }
        return codes
    }

    private fun normalise(code: String) = code.lowercase().filter(Char::isLetterOrDigit)

    private fun notify(user: UsersRecord, event: String, count: Int = 0) {
        mailer.send(
            "security-notice", user.email, Locale.forLanguageTag(user.locale),
            mapOf("name" to user.name, "messageKey" to "mail.security-notice.$event", "count" to count, "link" to "${props.baseUrl}/settings/profile"),
        )
    }

    private fun user(id: UUID): UsersRecord = dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne()!!

    /** What the authenticator app shows above the codes; names the instance when it isn't ours. */
    private fun issuer(): String {
        val host = runCatching { java.net.URI(props.baseUrl).host }.getOrNull()
        return if (host == null || host.endsWith("honestrobin.com") || host == "localhost") "Honest Robin" else "Honest Robin ($host)"
    }

    private fun wrongCode() = ValidationException("code", "That code doesn't match. Use the newest code in the app, and check that your phone's clock is right.")

    companion object {
        const val RECOVERY_CODES = 10

        // No 0/o, 1/l/i: easy to read off paper.
        private const val RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        fun svg(qr: QrCode, border: Int = 4): String {
            val size = qr.size + border * 2
            val path = buildString {
                for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.getModule(x, y)) append("M${x + border},${y + border}h1v1h-1z")
            }
            return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#ffffff"/><path d="$path" fill="#000000"/></svg>"""
        }
    }
}

@RestController
@RequestMapping("/api/v1/me/two_factor")
@Tag(name = "me", description = "The authenticated user")
class TwoFactorController(private val service: TwoFactorService) {
    @GetMapping
    fun status() = service.status(sessionUser())

    /** Needs a recent sign-in. Returns the secret and its QR code; scan, then confirm with `enable`. */
    @PostMapping("/setup")
    fun setup() = service.setup(sessionUser())

    @PostMapping("/enable")
    fun enable(@RequestBody body: TwoFactorCode) = service.enable(sessionUser(), body.code)

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disable() = service.disable(sessionUser())

    @PostMapping("/recovery_codes")
    fun recoveryCodes() = service.regenerateRecoveryCodes(sessionUser())

    private fun sessionUser(): UUID {
        val p = Current.principal()
        if (p.isApiToken) throw com.honestrobin.time.platform.web.ForbiddenException("Two-factor sign-in is managed in the web app, not with API tokens")
        return p.userId
    }
}
