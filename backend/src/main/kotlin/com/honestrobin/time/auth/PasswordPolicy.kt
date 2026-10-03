// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

@ConfigurationProperties(prefix = "honestrobin.passwords")
data class PasswordSettings(
    /**
     * Ask Have I Been Pwned whether a new password appeared in a breach. Only the first five
     * characters of the password's SHA-1 hash leave the server (k-anonymity), never the password.
     * If the service can't be reached, the password is accepted.
     */
    val breachCheck: Boolean = true,
    val breachApiUrl: String = "https://api.pwnedpasswords.com",
    val breachTimeout: Duration = Duration.ofSeconds(3),
)

/**
 * What makes a password acceptable (NIST SP 800-63B): long enough, not a well-known password,
 * not one character or a run like "1234567890", not mostly the person's name or email, and not
 * seen in a data breach. No rules about mixing character classes: they make passwords harder to
 * remember, not to guess.
 */
@Component
class PasswordPolicy(private val settings: PasswordSettings) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(settings.breachTimeout).build()

    private val common: Set<String> =
        javaClass.getResourceAsStream("/security/common-passwords.txt")!!.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.toHashSet() }

    /**
     * Returns what is wrong with the password, or null if it's fine. [context] holds words the
     * password shouldn't lean on: the person's email, name and workspace name.
     */
    fun problem(password: String, context: List<String?> = emptyList()): String? {
        if (password.length < MIN_LENGTH) return "Use at least $MIN_LENGTH characters"
        if (password.length > MAX_LENGTH) return "Use at most $MAX_LENGTH characters"
        val lower = password.lowercase()
        if (lower.toSet().size <= 2 || isRun(lower)) return "This password is too easy to guess. Try a few unrelated words."
        if (lower in common) return "This is one of the most used passwords. Choose another one."
        if (leansOn(lower, context)) return "Don't build the password from your name, email or workspace name."
        if (breached(password)) return "This password has appeared in a data breach, so attackers try it. Choose another one."
        return null
    }

    /** "1234567890", "abcdefghijk", "poiuytrewq": a stretch of the alphabet or a keyboard row. */
    private fun isRun(s: String) = RUNS.any { it.contains(s) }

    /** Too little is left once the context words are taken out. */
    private fun leansOn(lower: String, context: List<String?>): Boolean {
        val words = context.filterNotNull()
            .flatMap { listOf(it, it.substringBefore("@")) }
            .flatMap { it.lowercase().split(Regex("[^\\p{L}\\p{N}]+")) + it.lowercase().filter(Char::isLetterOrDigit) }
            .filter { it.length >= 4 }
            .plus(listOf("honestrobin", "honest robin"))
            .distinct()
            .sortedByDescending { it.length }
        var rest = lower
        for (w in words) rest = rest.replace(w, "")
        return rest != lower && rest.count(Char::isLetterOrDigit) < 6
    }

    private fun breached(password: String): Boolean {
        if (!settings.breachCheck) return false
        val hash = MessageDigest.getInstance("SHA-1").digest(password.toByteArray()).joinToString("") { "%02X".format(it) }
        return try {
            val request = HttpRequest.newBuilder(URI.create("${settings.breachApiUrl.trimEnd('/')}/range/${hash.take(5)}"))
                .timeout(settings.breachTimeout)
                .header("Add-Padding", "true")
                .header("User-Agent", "HonestRobin-Time")
                .GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("Breached-password check answered {}; accepting the password", response.statusCode())
                return false
            }
            val suffix = hash.drop(5)
            response.body().lineSequence().any { line ->
                line.substringBefore(':').trim().equals(suffix, ignoreCase = true) && (line.substringAfter(':').trim().toLongOrNull() ?: 0) > 0
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.warn("Breached-password check failed ({}); accepting the password", e.toString())
            false
        }
    }

    companion object {
        const val MIN_LENGTH = 10
        const val MAX_LENGTH = 256

        // Twice over, so runs that wrap around ("7890123456") count too; QWERTY, QWERTZ and AZERTY rows.
        private val RUNS = listOf("1234567890", "abcdefghijklmnopqrstuvwxyz", "qwertyuiop", "asdfghjkl", "zxcvbnm", "qwertzuiop", "yxcvbnm", "azertyuiop", "qsdfghjklm", "wxcvbn")
            .map { it + it }
            .flatMap { listOf(it, it.reversed()) }
    }
}
