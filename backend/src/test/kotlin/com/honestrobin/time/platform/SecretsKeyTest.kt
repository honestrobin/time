// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.platform.crypto.LocalKeyEncryptionKeyProvider
import com.honestrobin.time.platform.crypto.SecretsKeyCheck
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockStripe
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Security review, 4 October 2026: a lost secrets key stops the start instead of being replaced quietly. */
class SecretsKeyTest : IntegrationTest() {
    @Autowired lateinit var check: SecretsKeyCheck

    @Test
    fun `a missing key file stops the start while the database holds secrets`() {
        // Something encrypted with the key in use.
        signup().post("/api/v1/payments/stripe/key", mapOf("secret_key" to MockStripe.ACCOUNT_KEY, "webhook_secret" to "whsec_key_test")).expect(200)
        val file = Files.createTempDirectory("keys").resolve("secrets.key")
        val fresh = LocalKeyEncryptionKeyProvider(file.toString(), null)
        assertThat(fresh.unsaved).isEqualTo(file)
        assertThatThrownBy { check.check(fresh) }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("is missing").hasMessageContaining("Restore the key file")
        // Nothing was written, so the next start asks again instead of keeping the wrong key.
        assertThat(Files.exists(file)).isFalse()
    }

    @Test
    fun `a new key is written readable by its owner only, and read back as it was`() {
        val file = Files.createTempDirectory("keys").resolve("data/secrets.key")
        val fresh = LocalKeyEncryptionKeyProvider(file.toString(), null)
        fresh.save()
        assertThat(fresh.unsaved).isNull()
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
        val again = LocalKeyEncryptionKeyProvider(file.toString(), null)
        assertThat(again.unsaved).isNull()
        assertThat(again.keyId).isEqualTo(fresh.keyId)
        assertThat(again.unwrap(fresh.wrap(ByteArray(32) { it.toByte() }))).isEqualTo(ByteArray(32) { it.toByte() })
    }
}
