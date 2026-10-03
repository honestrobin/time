// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.files

import com.honestrobin.time.db.Tables.FILES
import com.honestrobin.time.db.tables.records.FilesRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.crypto.Tokens
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Blob storage behind an S3-like interface. Self-host uses the local filesystem driver. */
interface FileStorage {
    fun put(key: String, bytes: ByteArray, contentType: String)
    fun get(key: String): ByteArray
    fun delete(key: String)

    /** Stores a file without reading it into memory (large exports). */
    fun putFile(key: String, source: Path, contentType: String)

    /** Streams a stored blob; the caller closes the stream. */
    fun open(key: String): InputStream
}

@Component
class LocalFileStorage(props: HonestRobinProperties) : FileStorage {
    private val root: Path = Path.of(props.storage.localPath).toAbsolutePath().normalize()

    private fun resolve(key: String): Path {
        val p = root.resolve(key).normalize()
        require(p.startsWith(root)) { "Invalid storage key" }
        return p
    }

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".upload", ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun get(key: String): ByteArray = Files.readAllBytes(resolve(key))

    override fun putFile(key: String, source: Path, contentType: String) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".upload", ".tmp")
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun open(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }
}

data class StoredFile(val record: FilesRecord, val bytes: ByteArray)

@Service
class FileService(private val dsl: DSLContext, private val storage: FileStorage) {

    /**
     * Stores bytes and records metadata. The type is what the bytes are (see [FileTypes]), never
     * what the uploader or a remote server claims: files are served from the app's own origin.
     */
    fun store(accountId: UUID, filename: String, declaredMime: String, bytes: ByteArray, uploadedBy: UUID?, allowed: Set<String>? = null): FilesRecord {
        if (bytes.isEmpty()) throw ValidationException("file", "The file is empty")
        val mime = FileTypes.detect(bytes)
        if (allowed != null && mime !in allowed) throw ValidationException("file", "Unsupported file type: use a PDF or an image (JPEG, PNG, WebP, HEIC, GIF)")
        val key = "$accountId/${UUID.randomUUID()}"
        storage.put(key, bytes, mime)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) storage.delete(key)
                }
            })
        }
        val r = dsl.newRecord(FILES).apply {
            this.accountId = accountId
            storageKey = key
            this.filename = filename.take(255).ifBlank { "file" }
            this.mime = mime
            size = bytes.size.toLong()
            sha256 = Tokens.sha256Hex(bytes)
            this.uploadedBy = uploadedBy
        }
        r.store()
        return r
    }

    fun load(id: UUID): StoredFile {
        val r = dsl.selectFrom(FILES).where(FILES.ID.eq(id)).fetchOne() ?: throw NotFoundException("File")
        return StoredFile(r, storage.get(r.storageKey))
    }

    fun delete(id: UUID) {
        val r = dsl.selectFrom(FILES).where(FILES.ID.eq(id)).fetchOne() ?: return
        r.delete()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = storage.delete(r.storageKey)
        })
    }

    companion object {
        val RECEIPT_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/gif", "application/pdf")
    }
}

/** File types from the first bytes of a file. Anything not recognised is just bytes. */
object FileTypes {
    const val OCTET = "application/octet-stream"

    /** Types that are safe to show in the browser on our origin. */
    val INLINE = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/gif", "application/pdf")

    fun detect(b: ByteArray): String {
        fun at(offset: Int, vararg sig: Int) = b.size >= offset + sig.size && sig.indices.all { (b[offset + it].toInt() and 0xff) == sig[it] }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return when {
            ascii(0, "%PDF-") -> "application/pdf"
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "heif")) -> "image/heic"
            else -> OCTET
        }
    }
}
