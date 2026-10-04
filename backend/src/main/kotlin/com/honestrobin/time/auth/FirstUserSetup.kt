// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.HonestRobinProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The first person to sign up becomes the instance's admin. Without a check, whoever reaches an
 * empty instance first could claim it: a server just started on the internet, or an existing one
 * that came up on an empty database by mistake. So the first sign-up needs a setup code that only
 * someone with access to the server can see: HONESTROBIN_SETUP_CODE, or one made at startup and
 * written to the log. Once anyone has signed up, the code isn't needed or shown again.
 */
@Component
class FirstUserSetup(props: HonestRobinProperties) {
    private val configured = props.setupCode.isNotBlank()

    /** Several app servers each make their own code; set HONESTROBIN_SETUP_CODE so they agree. */
    private val code: String = props.setupCode.trim().ifEmpty { generate() }

    fun matches(given: String?): Boolean =
        MessageDigest.isEqual(normalise(given.orEmpty()).toByteArray(), normalise(code).toByteArray())

    fun describe(): String =
        if (configured) {
            "the setup code set in HONESTROBIN_SETUP_CODE"
        } else {
            "this setup code: $code (it changes when the app restarts; set HONESTROBIN_SETUP_CODE to choose one)"
        }

    private fun normalise(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private fun generate(): String {
        // 60 bits, in letters and digits that can't be mistaken for each other.
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..12).map { alphabet[random.nextInt(alphabet.length)] }.chunked(4).joinToString("-") { it.joinToString("") }
    }
}

/** Says, at startup, how to set up an instance nobody has signed up to yet. */
@Component
class FirstUserSetupNotice(private val auth: AuthService, private val setup: FirstUserSetup, private val props: HonestRobinProperties) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (auth.hasAnyUser()) return
        LoggerFactory.getLogger(javaClass).info(
            "Nobody has signed up yet. The first person to sign up at {}/signup becomes this instance's admin, with {}",
            props.baseUrl, setup.describe(),
        )
    }
}
