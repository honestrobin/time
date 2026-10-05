// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.platform.edition.Edition
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "honestrobin")
data class HonestRobinProperties(
    val edition: Edition = Edition.SELFHOST,
    /** Public URL of this instance, used in emails and redirects. */
    val baseUrl: String = "http://localhost:8080",
    val signupMode: SignupMode = SignupMode.FIRST_USER_ONLY,
    val db: Db = Db(),
    val session: Session = Session(),
    val mail: Mail = Mail(),
    val secrets: Secrets = Secrets(),
    val storage: Storage = Storage(),
    /** Bearer token for scraping /actuator/prometheus; blank keeps the endpoint closed. */
    val metricsToken: String = "",
    /** The code the first sign-up needs. Blank: one is made at startup and written to the log. */
    val setupCode: String = "",
) {
    enum class SignupMode {
        /** Anyone may create an account (Honest Robin Cloud). */
        OPEN,

        /** Only the very first user may sign up; everyone else joins by invitation (self-host default). */
        FIRST_USER_ONLY,

        /** Nobody may sign up; people join by invitation only. */
        INVITE_ONLY,
    }

    data class Db(
        /**
         * Role every transaction switches to with SET ROLE, so row-level security applies even to a
         * superuser. Without it the app doesn't start. Blank: transactions run as the database user
         * itself, which must then not bypass row-level security (RowLevelSecurityCheck).
         */
        val appRole: String = "honestrobin_app",
    )

    data class Session(
        val cookieName: String = "honestrobin_session",
        /** A session unused this long ends. */
        val ttl: Duration = Duration.ofDays(30),
        /** A session ends this long after sign-in, however much it is used. */
        val maxLifetime: Duration = Duration.ofDays(90),
        /** How recently someone must have proved who they are for sensitive actions. */
        val reauthWindow: Duration = Duration.ofMinutes(10),
    )

    data class Mail(
        val from: String = "Honest Robin <no-reply@localhost>",
    )

    data class Secrets(
        /** Key-encryption key file for envelope encryption (self-host). Generated on first start if missing. */
        val keyFile: String = "./data/secrets.key",
    )

    data class Storage(
        val driver: String = "local",
        val localPath: String = "./data/files",
    )

    val secureCookies: Boolean get() = baseUrl.startsWith("https://")
}
