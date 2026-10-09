// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.analytics.ClientAnalytics
import com.honestrobin.time.analytics.ClientAnalyticsConfig
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.edition.EditionInfo
import com.honestrobin.time.platform.features.Features
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.web.ForbiddenException
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AuthConfig(
    val edition: String,
    val version: String,
    val signupAllowed: Boolean,
    val needsSetup: Boolean,
    val magicLinks: Boolean,
    /** Whether this instance sends email. Without it, sign-in links and invitations are in the server's log. */
    val emailConfigured: Boolean,
    val oauthProviders: List<String>,
    /** The parts of Time switched on for this instance, by name. The web app shows only these, beside the core. */
    val features: List<String>,
    val marketingUrl: String?,
    /** Where the web app sends page views; null when it sends none (always, on self-hosted instances). */
    val analytics: ClientAnalyticsConfig?,
)

data class LoginRequest(val email: String, val password: String)
data class ReauthRequest(val password: String? = null, val code: String? = null)
data class SecondFactorRequest(val challenge: String, val code: String? = null, val recoveryCode: String? = null)
data class EmailRequest(val email: String)
data class TokenRequest(val token: String)
data class PasswordResetRequest(val token: String, val password: String)
data class SignupRequest(
    val name: String,
    val email: String,
    val password: String,
    val accountName: String,
    val timezone: String? = null,
    val defaultCurrency: String? = null,
    val locale: String? = null,
    /** 1 (Monday) to 7 (Sunday). */
    val weekStart: Int? = null,
    /** Only for the very first sign-up of an instance: the setup code from the server's log. */
    val setupCode: String? = null,
)
data class SignedInResponse(
    val userId: UUID,
    val accountId: UUID?,
    /** True when a code from the authenticator app is needed: send it with [challenge] to `/auth/two_factor`. */
    val twoFactorRequired: Boolean = false,
    val challenge: String? = null,
)
data class AcceptInviteRequest(val token: String, val name: String? = null, val password: String? = null)

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Sign-in for the web app (session cookies). API clients use personal access tokens instead.")
class AuthController(
    private val auth: AuthService,
    private val sessions: SessionService,
    private val props: HonestRobinProperties,
    private val edition: EditionInfo,
    private val analytics: ClientAnalytics,
    private val mailer: com.honestrobin.time.platform.mail.Mailer,
    private val features: Features,
) {
    @GetMapping("/config")
    fun config() = AuthConfig(
        edition = edition.name,
        version = edition.version,
        signupAllowed = auth.signupAllowed(),
        needsSetup = !auth.hasAnyUser(),
        magicLinks = true,
        emailConfigured = mailer.canDeliver,
        oauthProviders = emptyList(),
        features = features.on,
        marketingUrl = edition.marketingUrl,
        analytics = analytics.config(),
    )

    @PostMapping("/signup")
    fun signup(@RequestBody body: SignupRequest, request: HttpServletRequest): ResponseEntity<SignedInResponse> {
        val result = auth.signup(
            SignupInput(body.name, body.email, body.password, body.accountName, body.timezone, body.defaultCurrency, body.locale, body.weekStart, body.setupCode),
            request.remoteAddr,
            request.getHeader(HttpHeaders.USER_AGENT),
        )
        return signedIn(result, HttpStatus.CREATED)
    }

    @PostMapping("/login")
    fun login(@RequestBody body: LoginRequest, request: HttpServletRequest) =
        signedIn(auth.login(body.email, body.password, request.remoteAddr, request.getHeader(HttpHeaders.USER_AGENT)))

    @PostMapping("/magic_link")
    fun magicLink(@RequestBody body: EmailRequest): ResponseEntity<Unit> {
        auth.requestMagicLink(body.email)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/magic_link/consume")
    fun consumeMagicLink(@RequestBody body: TokenRequest, request: HttpServletRequest) =
        signedIn(auth.consumeMagicLink(body.token, request.remoteAddr, request.getHeader(HttpHeaders.USER_AGENT)))

    @PostMapping("/password_reset")
    fun passwordReset(@RequestBody body: EmailRequest): ResponseEntity<Unit> {
        auth.requestPasswordReset(body.email)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/password_reset/consume")
    fun consumePasswordReset(@RequestBody body: PasswordResetRequest, request: HttpServletRequest) =
        signedIn(auth.resetPassword(body.token, body.password, request.remoteAddr, request.getHeader(HttpHeaders.USER_AGENT)))

    @PostMapping("/invite/lookup")
    fun lookupInvite(@RequestBody body: TokenRequest) = auth.lookupInvite(body.token)

    @PostMapping("/invite/accept")
    fun acceptInvite(@RequestBody body: AcceptInviteRequest, request: HttpServletRequest) =
        signedIn(auth.acceptInvite(body.token, body.name, body.password, request.remoteAddr, request.getHeader(HttpHeaders.USER_AGENT)))

    /** Confirms the email address from the link sent at sign-up. */
    @PostMapping("/verify_email")
    fun verifyEmail(@RequestBody body: TokenRequest): ResponseEntity<Unit> {
        auth.verifyEmail(body.token, Current.principalOrNull()?.userId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/verify_email/resend")
    fun resendVerification(): ResponseEntity<Unit> {
        auth.resendVerification(Current.principal().userId)
        return ResponseEntity.noContent().build()
    }

    /** The second step of signing in, with two-factor sign-in on: the app's code or a recovery code. */
    @PostMapping("/two_factor")
    fun twoFactor(@RequestBody body: SecondFactorRequest, request: HttpServletRequest) =
        signedIn(auth.completeSecondFactor(body.challenge, body.code, body.recoveryCode, request.remoteAddr, request.getHeader(HttpHeaders.USER_AGENT)))

    /** Confirms the password again, for actions that need a recent sign-in (`reauth_required`). */
    @PostMapping("/reauth")
    fun reauth(@RequestBody body: ReauthRequest, request: HttpServletRequest): ResponseEntity<Unit> {
        val p = Current.principal()
        val session = p.sessionId ?: throw ForbiddenException("Only browser sessions confirm a password; API tokens cannot")
        auth.reauthenticate(p.userId, session, body.password, body.code, request.remoteAddr)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/logout")
    fun logout(): ResponseEntity<Unit> {
        Current.principalOrNull()?.sessionId?.let { sessions.revoke(it) }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString()).build()
    }

    private fun signedIn(result: SignedIn, status: HttpStatus = HttpStatus.OK): ResponseEntity<SignedInResponse> {
        if (result.sessionToken == null) {
            return ResponseEntity.ok(SignedInResponse(result.userId, result.accountId, twoFactorRequired = true, challenge = result.challenge))
        }
        return ResponseEntity.status(status)
            .header(HttpHeaders.SET_COOKIE, sessions.cookie(result.sessionToken).toString())
            .body(SignedInResponse(result.userId, result.accountId))
    }
}
