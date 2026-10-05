// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.crypto

import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.db.Tx
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wraps and unwraps data-encryption keys with a key from a file or HONESTROBIN_SECRETS_KEY, in both editions. There's no KMS (decision record 0025). */
interface KeyEncryptionKeyProvider {
    val keyId: String
    fun wrap(dek: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Envelope encryption for stored secrets (integration credentials, import tokens).
 * Each secret gets its own random AES-256 data key, which is itself encrypted by the key-encryption key.
 */
@Component
class SecretBox(private val kek: KeyEncryptionKeyProvider) {
    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        val dek = ByteArray(32).also(random::nextBytes)
        val wrapped = kek.wrap(dek)
        val body = Aes.encrypt(dek, plaintext.toByteArray(Charsets.UTF_8))
        val keyId = kek.keyId.toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(1 + 1 + keyId.size + 2 + wrapped.size + body.size)
            .put(VERSION).put(keyId.size.toByte()).put(keyId)
            .putShort(wrapped.size.toShort()).put(wrapped).put(body)
        return Base64.getEncoder().encodeToString(out.array())
    }

    fun decrypt(ciphertext: String): String {
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext))
        require(buf.get() == VERSION) { "Unknown secret format" }
        val keyId = ByteArray(buf.get().toInt()).also(buf::get)
        check(String(keyId, Charsets.UTF_8) == kek.keyId) { "Secret was encrypted with a different key" }
        val wrapped = ByteArray(buf.getShort().toInt()).also(buf::get)
        val body = ByteArray(buf.remaining()).also(buf::get)
        return String(Aes.decrypt(kek.unwrap(wrapped), body), Charsets.UTF_8)
    }

    private companion object {
        const val VERSION: Byte = 1
    }
}

internal object Aes {
    private val random = SecureRandom()

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }
}

/**
 * Key-encryption key from `HONESTROBIN_SECRETS_KEY` (base64) or a key file, generated on first start.
 * Losing it makes two-factor secrets and stored credentials unreadable, so a key made on this start
 * is only written once [SecretsKeyCheck] has found nothing encrypted with an earlier one.
 */
@Component
class LocalKeyEncryptionKeyProvider(keyFile: String, envKey: String?) : KeyEncryptionKeyProvider {
    @Autowired
    constructor(props: HonestRobinProperties) : this(props.secrets.keyFile, System.getenv("HONESTROBIN_SECRETS_KEY"))

    private val path: Path = Path.of(keyFile)
    private val key: ByteArray
    final override val keyId: String

    /** A key made on this start, kept in memory until [save]; null when one was found. */
    final var unsaved: Path? = null
        private set

    init {
        val given = envKey?.takeIf { it.isNotBlank() }?.let(::decode) ?: if (Files.exists(path)) decode(Files.readString(path).trim()) else null
        key = given ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        if (given == null) unsaved = path
        keyId = "local:" + Tokens.sha256Hex(key).take(8)
    }

    override fun wrap(dek: ByteArray) = Aes.encrypt(key, dek)

    override fun unwrap(wrapped: ByteArray) = Aes.decrypt(key, wrapped)

    /** Writes a new key, readable by its owner only from the moment it exists. */
    fun save() {
        val target = unsaved ?: return
        Files.createDirectories(target.toAbsolutePath().parent)
        val ownerOnly = runCatching { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }.getOrNull()
        val created = if (ownerOnly != null && runCatching { Files.createFile(target, ownerOnly) }.isSuccess) target else Files.createFile(target)
        Files.writeString(created, Base64.getEncoder().encodeToString(key))
        unsaved = null
        LoggerFactory.getLogger(javaClass).warn("Generated a new secrets key at {}. Back it up together with your database.", target.toAbsolutePath())
    }

    private fun decode(s: String) = Base64.getDecoder().decode(s).also { require(it.size == 32) { "Secrets key must be 32 bytes (base64)" } }
}

/**
 * When this start made a new secrets key, checks that the database holds nothing encrypted with
 * an earlier one before keeping it. If it does, the key file went missing (a lost volume, a
 * restore without it): starting anyway would leave every two-factor sign-in and connection
 * unreadable, so the app refuses and says how to get the key back (security review,
 * 4 October 2026).
 */
@Component
class SecretsKeyCheck(private val dsl: DSLContext, private val tx: Tx) {
    fun check(kek: KeyEncryptionKeyProvider) {
        val local = kek as? LocalKeyEncryptionKeyProvider ?: return
        val missing = local.unsaved ?: return
        val encrypted = listOf(USERS.TOTP_SECRET_ENCRYPTED, USERS.TOTP_PENDING_ENCRYPTED, INTEGRATIONS.CREDENTIALS_ENCRYPTED, IMPORT_JOBS.TOKEN_ENCRYPTED, IMPORT_JOBS.REFRESH_TOKEN_ENCRYPTED)
        val held = tx.system { encrypted.any { column -> dsl.fetchExists(dsl.selectOne().from(column.table).where(column.isNotNull)) } }
        check(!held) {
            "The secrets key ${missing.toAbsolutePath()} is missing, but the database holds secrets encrypted with one " +
                "(two-factor sign-in, connections, import tokens). Starting with a new key would make them unreadable. " +
                "Restore the key file from the backup you made with the database, or set HONESTROBIN_SECRETS_KEY to it."
        }
        local.save()
    }
}

@Configuration
class SecretsKeyConfig {
    @Bean
    fun secretsKeyStartupCheck(check: SecretsKeyCheck, kek: KeyEncryptionKeyProvider) = ApplicationRunner { check.check(kek) }
}
