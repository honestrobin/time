// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.security

import com.honestrobin.time.platform.web.ApiError
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.util.matcher.RequestMatcher
import java.util.function.Supplier

@Configuration
@EnableWebSecurity
class SecurityConfig(private val objectMapper: ObjectMapper) {

    @Bean
    fun securityFilterChain(http: HttpSecurity, authFilter: AuthFilter, csp: ObjectProvider<CspContributor>, props: com.honestrobin.time.platform.HonestRobinProperties): SecurityFilterChain {
        // Metrics are for the operator's scraper, not the public: a bearer token, or nothing.
        val metricsAllowed = org.springframework.security.authorization.AuthorizationManager<org.springframework.security.web.access.intercept.RequestAuthorizationContext> { _, ctx ->
            val expected = props.metricsToken
            val given = ctx.request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
            org.springframework.security.authorization.AuthorizationDecision(
                expected.isNotBlank() && java.security.MessageDigest.isEqual(given.toByteArray(), expected.toByteArray()),
            )
        }
        val bearerRequest = RequestMatcher { it.getHeader("Authorization")?.startsWith("Bearer ") == true }
        http
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse().apply { setCookiePath("/") })
                    .csrfTokenRequestHandler(SpaCsrfTokenRequestHandler())
                    // API tokens are not ambient credentials; webhooks are verified by signature.
                    .ignoringRequestMatchers(bearerRequest)
                    .ignoringRequestMatchers("/webhooks/**")
                    // Device sign-in (the browser extension): no cookies involved, and the answers
                    // (codes, tokens) are only readable by the caller, not a cross-site page.
                    .ignoringRequestMatchers("/api/v1/auth/device", "/api/v1/auth/device/token")
                    // Authentication happens per request (stateless); don't rotate the token on every request.
                    .sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy())
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .anonymous { }
            .authorizeHttpRequests { auth ->
                // A live-update stream (server-sent events) ends with an async dispatch of a request
                // that was authorized when it started; checking it again would fail on the committed response.
                auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                auth.requestMatchers("/api/v1/auth/**", "/api/v1/public/**", "/v3/api-docs/**", "/webhooks/**").permitAll()
                auth.requestMatchers("/api/**").authenticated()
                auth.requestMatchers("/actuator/health/**").permitAll()
                auth.requestMatchers("/actuator/prometheus").access(metricsAllowed)
                auth.requestMatchers("/actuator/**").denyAll()
                auth.anyRequest().permitAll()
            }
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint(JsonEntryPoint())
                ex.accessDeniedHandler(JsonAccessDenied())
            }
            .headers { h ->
                h.contentSecurityPolicy {
                    it.policyDirectives(contentSecurityPolicy(csp.orderedStream().map { c -> c.sources() }.toList()))
                }
                h.frameOptions { it.deny() }
                h.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
                h.permissionsPolicyHeader { it.policy("camera=(), microphone=(), geolocation=()") }
            }
            .addFilterBefore(authFilter, AnonymousAuthenticationFilter::class.java)
        return http.build()
    }

    /** AuthFilter runs inside the security chain only, not as a standalone servlet filter. */
    @Bean
    fun authFilterRegistration(filter: AuthFilter) = FilterRegistrationBean(filter).apply { isEnabled = false }

    private inner class JsonEntryPoint : AuthenticationEntryPoint {
        override fun commence(request: HttpServletRequest, response: HttpServletResponse, e: AuthenticationException) =
            write(response, HttpStatus.UNAUTHORIZED, ApiError("unauthenticated", "Please sign in"))
    }

    private inner class JsonAccessDenied : AccessDeniedHandler {
        override fun handle(request: HttpServletRequest, response: HttpServletResponse, e: org.springframework.security.access.AccessDeniedException) {
            val csrf = e is org.springframework.security.web.csrf.CsrfException
            write(
                response,
                HttpStatus.FORBIDDEN,
                if (csrf) ApiError("csrf_failed", "Your session expired. Reload the page and try again.") else ApiError("forbidden", "You don't have permission to do this"),
            )
        }
    }

    private fun write(response: HttpServletResponse, status: HttpStatus, body: ApiError) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, body)
    }
}

/**
 * CSRF for a SPA (Spring Security reference, "Single-Page Applications"): the token is
 * exposed in the XSRF-TOKEN cookie and echoed back in the X-XSRF-TOKEN header.
 */
class SpaCsrfTokenRequestHandler : CsrfTokenRequestHandler {
    private val plain = CsrfTokenRequestAttributeHandler()
    private val xor = XorCsrfTokenRequestAttributeHandler()

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, csrfToken: Supplier<CsrfToken>) {
        xor.handle(request, response, csrfToken)
        csrfToken.get() // render the cookie on every response
    }

    override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? {
        val header = request.getHeader(csrfToken.headerName)
        return if (!header.isNullOrBlank()) plain.resolveCsrfTokenValue(request, csrfToken) else xor.resolveCsrfTokenValue(request, csrfToken)
    }
}
