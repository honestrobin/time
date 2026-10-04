// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.accounts.MembershipResolver
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.DEVICE_AUTHORIZATIONS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.RateLimiter
import com.honestrobin.time.platform.security.RecentAuth
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ForbiddenException
import com.honestrobin.time.platform.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class DeviceAuthorizationRequest(
    /** Shown to the person approving, e.g. "Browser extension (Firefox)". */
    val clientName: String,
)

data class DeviceAuthorizationStart(
    /** The device's secret: send it to `/auth/device/token` until the person approves. */
    val deviceCode: String,
    /** Shown on the device; the person checks it matches on the approval page. */
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Long,
    /** Seconds to wait between polls. */
    val interval: Int,
)

data class DeviceTokenRequest(val deviceCode: String)

data class DeviceToken(val token: String, val accountId: UUID, val accountName: String, val email: String)

data class DeviceAuthorizationView(val userCode: String, val clientName: String, val status: String, val createdAt: Instant, val expiresAt: Instant)

/**
 * Signing in a device, the browser extension above all, without typing a password into it
 * (RFC 8628, device authorization grant): the device asks for a code, the person approves it in
 * the web app (where they are signed in, with two-factor sign-in if they use it), and the
 * device collects an API token for the account the person chose.
 */
@Service
class DeviceAuthorizationService(
    private val dsl: DSLContext,
    private val props: HonestRobinProperties,
    private val tokens: ApiTokenService,
    private val memberships: MembershipResolver,
    private val recentAuth: RecentAuth,
    private val limiter: RateLimiter,
    private val notices: SecurityNotices,
) {
    private val random = SecureRandom()

    @Transactional
    fun start(clientName: String, ip: String?): DeviceAuthorizationStart = DbContext.system {
        if (ip != null && !limiter.tryAcquire("device:$ip", 30, Duration.ofHours(1))) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", "Too many sign-in attempts from this network. Try again in an hour.")
        }
        val name = clientName.trim().take(80).ifBlank { "Device" }
        val deviceCode = Tokens.generate()
        val userCode = (1..8).map { USER_CODE_ALPHABET[random.nextInt(USER_CODE_ALPHABET.length)] }.joinToString("").let { "${it.take(4)}-${it.drop(4)}" }
        dsl.insertInto(DEVICE_AUTHORIZATIONS)
            .set(DEVICE_AUTHORIZATIONS.DEVICE_CODE_HASH, Tokens.hash(deviceCode))
            .set(DEVICE_AUTHORIZATIONS.USER_CODE, userCode)
            .set(DEVICE_AUTHORIZATIONS.CLIENT_NAME, name)
            .set(DEVICE_AUTHORIZATIONS.EXPIRES_AT, Instant.now().plus(TTL))
            .execute()
        val uri = "${props.baseUrl}/device"
        DeviceAuthorizationStart(deviceCode, userCode, uri, "$uri?code=${URLEncoder.encode(userCode, Charsets.UTF_8)}", TTL.seconds, POLL_SECONDS)
    }

    /** For the approval page: which device asks. */
    @Transactional(readOnly = true)
    fun lookup(userCode: String): DeviceAuthorizationView = DbContext.system {
        val r = pending(userCode)
        DeviceAuthorizationView(r.userCode, r.clientName, r.status, r.createdAt, r.expiresAt)
    }

    /** The signed-in person lets the device act as them in [member]'s account. Creates a token, so it needs a recent sign-in. */
    @Transactional
    fun approve(member: Member, userCode: String) {
        recentAuth.require()
        member.requireWritable()
        DbContext.system {
            val r = pending(userCode)
            r.status = "approved"
            r.membershipId = member.membershipId
            r.approvedAt = Instant.now()
            r.store()
        }
    }

    @Transactional
    fun deny(userCode: String) = DbContext.system {
        val r = pending(userCode)
        r.status = "denied"
        r.store()
    }

    /** The device collects its token, once. Until approval it hears `authorization_pending`. */
    @Transactional
    fun collect(deviceCode: String): DeviceToken = DbContext.system {
        val r = dsl.selectFrom(DEVICE_AUTHORIZATIONS).where(DEVICE_AUTHORIZATIONS.DEVICE_CODE_HASH.eq(Tokens.hash(deviceCode))).forUpdate().fetchOne()
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "invalid_grant", "Unknown device code. Start signing in again.")
        // An approved code must be collected soon after: the device is polling every few seconds.
        val stale = if (r.status == "approved") r.approvedAt?.isBefore(Instant.now().minus(COLLECT_WITHIN)) ?: true else r.expiresAt.isBefore(Instant.now())
        if (stale) throw ApiException(HttpStatus.BAD_REQUEST, "expired_token", "The code expired. Start signing in again.")
        when (r.status) {
            "pending" -> throw ApiException(HttpStatus.BAD_REQUEST, "authorization_pending", "Waiting for you to approve the code in Honest Robin.")
            "denied" -> throw ApiException(HttpStatus.BAD_REQUEST, "access_denied", "Signing in was declined.")
            "used" -> throw ApiException(HttpStatus.BAD_REQUEST, "invalid_grant", "This code was already used. Start signing in again.")
        }
        val member = r.membershipId?.let(memberships::byMembershipId)
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "access_denied", "That account is no longer available.")
        val created = tokens.createForDevice(member, r.clientName)
        r.status = "used"
        r.store()
        // Tell the person, so a device they didn't connect (a phished code) doesn't go unnoticed.
        dsl.selectFrom(USERS).where(USERS.ID.eq(member.userId)).fetchOne()?.let { notices.send(it, "device-connected", r.clientName) }
        val account = dsl.select(ACCOUNTS.NAME, MEMBERSHIPS.EMAIL).from(MEMBERSHIPS).join(ACCOUNTS).on(ACCOUNTS.ID.eq(MEMBERSHIPS.ACCOUNT_ID))
            .where(MEMBERSHIPS.ID.eq(member.membershipId)).fetchOne()!!
        DeviceToken(created.token, member.accountId, account.value1(), account.value2())
    }

    @Transactional
    fun purgeExpired(): Int = DbContext.system {
        dsl.deleteFrom(DEVICE_AUTHORIZATIONS).where(DEVICE_AUTHORIZATIONS.EXPIRES_AT.lt(Instant.now().minus(Duration.ofDays(1)))).execute()
    }

    private fun pending(userCode: String) =
        dsl.selectFrom(DEVICE_AUTHORIZATIONS)
            .where(DEVICE_AUTHORIZATIONS.USER_CODE.eq(normalise(userCode)))
            .and(DEVICE_AUTHORIZATIONS.STATUS.eq("pending"))
            .and(DEVICE_AUTHORIZATIONS.EXPIRES_AT.gt(Instant.now()))
            .fetchOne() ?: throw NotFoundException("Code")

    private fun normalise(code: String): String {
        val c = code.uppercase().filter { it in USER_CODE_ALPHABET }
        return if (c.length == 8) "${c.take(4)}-${c.drop(4)}" else code.trim().uppercase()
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)
        const val POLL_SECONDS = 2

        /** How long after approval the device may collect its token. */
        val COLLECT_WITHIN: Duration = Duration.ofMinutes(10)

        // RFC 8628 §6.1: consonants only, so no words, no 0/O or 1/I confusion.
        private const val USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"
    }
}

@RestController
@Tag(name = "auth", description = "Sign-in for the web app (session cookies). API clients use personal access tokens instead.")
class DeviceAuthorizationController(private val service: DeviceAuthorizationService) {
    @PostMapping("/api/v1/auth/device")
    @Operation(summary = "Start signing in a device (RFC 8628): returns a code to show and where to approve it")
    fun start(@RequestBody body: DeviceAuthorizationRequest, request: HttpServletRequest) = service.start(body.clientName, request.remoteAddr)

    @PostMapping("/api/v1/auth/device/token")
    @Operation(summary = "Collect the device's API token once approved; `authorization_pending` until then")
    fun token(@RequestBody body: DeviceTokenRequest) = service.collect(body.deviceCode)

    @GetMapping("/api/v1/device_authorizations/{userCode}")
    fun lookup(@PathVariable userCode: String) = service.lookup(userCode)

    @PostMapping("/api/v1/device_authorizations/{userCode}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approve(@PathVariable userCode: String) {
        if (Current.principal().isApiToken) throw ForbiddenException("Approve devices in the web app")
        service.approve(Current.member(), userCode)
    }

    @PostMapping("/api/v1/device_authorizations/{userCode}/deny")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deny(@PathVariable userCode: String) {
        Current.principal()
        service.deny(userCode)
    }
}
