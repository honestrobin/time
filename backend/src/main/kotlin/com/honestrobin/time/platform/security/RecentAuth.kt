// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.security

import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ForbiddenException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Sensitive actions (creating API tokens, deleting or exporting the account, changing where money
 * goes) need more than a session cookie: the person must have signed in or confirmed their
 * password within the last few minutes. The web app answers `reauth_required` by asking for the
 * password and retrying.
 */
@Component
class RecentAuth(private val props: HonestRobinProperties) {

    /** Sessions must be recent; API tokens are refused. */
    fun require() = check(allowApiTokens = false)

    /** Sessions must be recent; API tokens are a deliberate credential and may go ahead (automation). */
    fun requireUnlessApiToken() = check(allowApiTokens = true)

    private fun check(allowApiTokens: Boolean) {
        val principal = Current.principalOrNull() ?: return // jobs and other system work
        if (principal.isApiToken) {
            if (allowApiTokens) return
            throw ForbiddenException("Sign in to the web app to do this; API tokens cannot")
        }
        val at = principal.authenticatedAt
        if (at == null || at.isBefore(Instant.now().minus(props.session.reauthWindow))) {
            throw ApiException(HttpStatus.FORBIDDEN, CODE, "Confirm it's you: enter your password again to continue.")
        }
    }

    companion object {
        const val CODE = "reauth_required"
    }
}
