// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.security

import com.honestrobin.time.accounts.MembershipResolver
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.web.ForbiddenException
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The `state` of an OAuth connect flow (Stripe Connect, QuickBooks Online, Xero). The provider
 * sends the browser back with it, so it has to prove two things: which account asked, and that
 * the browser coming back is the one that started. Without the second, someone could start
 * connecting in their own workspace and get another business's admin to authorise it, attaching
 * that business's books or Stripe account to theirs.
 *
 * The state is `<account id>.<random token>`. The integration's settings keep only the token's
 * hash, the session that started the flow and when it expires, so the audit log and exports hold
 * nothing anyone could use. Finishing needs the same session, still an admin of the account,
 * within [TTL].
 */
@Component
class OAuthStates(private val memberships: MembershipResolver) {

    /** Starts a flow for [m]: the state to send, and what to keep (under the settings key [KEY]). */
    fun start(m: Member): Pair<String, Map<String, String>> {
        val session = Current.principal().sessionId ?: throw ForbiddenException("Sign in to the web app to do this; API tokens cannot")
        val token = Tokens.generate(24)
        return "${m.accountId}.$token" to mapOf(
            "state_sha256" to hex(Tokens.hash(token)),
            "session_id" to session.toString(),
            "membership_id" to m.membershipId.toString(),
            "expires_at" to Instant.now().plus(TTL).toString(),
        )
    }

    /** The account a returned state names, and its token; null if it isn't one of ours. */
    fun parse(state: String?): Pair<UUID, String>? {
        val (account, token) = state?.split('.', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return runCatching { UUID.fromString(account) }.getOrNull()?.let { it to token }
    }

    /** Whether [token] finishes the flow kept as [pending] in [accountId], for whoever is signed in now. */
    fun matches(pending: Any?, accountId: UUID, token: String): Boolean {
        val kept = (pending as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v?.toString() } ?: return false
        val principal = Current.principalOrNull() ?: return false
        if (principal.sessionId == null || kept["session_id"] != principal.sessionId.toString()) return false
        val expires = kept["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        if (Instant.now().isAfter(expires)) return false
        if (!MessageDigest.isEqual(hex(Tokens.hash(token)).toByteArray(), kept["state_sha256"].orEmpty().toByteArray())) return false
        val member = memberships.resolve(principal.userId, accountId) ?: return false
        return member.isAdmin && !member.isReadOnly && member.membershipId.toString() == kept["membership_id"]
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY = "oauth"
        val TTL: Duration = Duration.ofMinutes(30)
    }
}
