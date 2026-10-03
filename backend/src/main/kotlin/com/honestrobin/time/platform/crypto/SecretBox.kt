// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.crypto

import com.honestrobin.time.platform.HonestRobinProperties
import org.slf4j.LoggerFactory
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

/** Wraps and unwraps data-encryption keys. Self-host: a local key file. Cloud: a KMS-backed implementation. */
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
 * Losing this key makes stored integration credentials unreadable (they can be re-entered).
 */
@Component
class LocalKeyEncryptionKeyProvider(props: HonestRobinProperties) : KeyEncryptionKeyProvider {
    private val key: ByteArray = loadOrCreate(props.secrets.keyFile)
    override val keyId: String = "local:" + Tokens.sha256Hex(key).take(8)

    override fun wrap(dek: ByteArray) = Aes.encrypt(key, dek)

    override fun unwrap(wrapped: ByteArray) = Aes.decrypt(key, wrapped)

    private fun loadOrCreate(file: String): ByteArray {
        System.getenv("HONESTROBIN_SECRETS_KEY")?.takeIf { it.isNotBlank() }?.let { return decode(it) }
        val path = Path.of(file)
        if (Files.exists(path)) return decode(Files.readString(path).trim())
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.writeString(path, Base64.getEncoder().encodeToString(fresh))
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        LoggerFactory.getLogger(javaClass).warn("Generated a new secrets key at {}. Back it up together with your database.", path.toAbsolutePath())
        return fresh
    }

    private fun decode(s: String) = Base64.getDecoder().decode(s).also { require(it.size == 32) { "Secrets key must be 32 bytes (base64)" } }
}
