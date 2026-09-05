package com.kiss.backend.service

import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.stereotype.Service
import org.springframework.util.FileSystemUtils
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

data class StoredFile(
    val key: String,
    val size: Long,
    val lastModified: Instant
)

/**
 * Single-node file store (no object storage, NO metadata sidecar).
 *
 * Layout under [baseDir]:
 *   documents/{hash}.{origExt}                    originals
 *   scratch/doc-{hash}/{chunks,embeddings}.json   claim-check intermediate data
 */
@Service
class LocalStorageService(
    @Value("\${faldony.storage.base-dir}") private val baseDir: String
) {

    private val logger = LoggerFactory.getLogger(LocalStorageService::class.java)

    private val root: Path = Path.of(baseDir).toAbsolutePath().normalize()

    init {
        listOf(DOCUMENTS_PREFIX, SCRATCH_PREFIX).forEach { Files.createDirectories(root.resolve(it)) }
        logger.info("File store ready at {}", root)
    }

    companion object {
        const val DOCUMENTS_PREFIX = "documents"
        const val SCRATCH_PREFIX = "scratch"
    }

    // ------------------------------------------------------------------- core

    fun put(key: String, stream: InputStream) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            Files.newOutputStream(tmp).use { out -> stream.copyTo(out) }
            // ATOMIC_MOVE ensures concurrent readers never observe partial files
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * File size in bytes; throws [java.nio.file.NoSuchFileException] if missing.
     */
    fun fileSize(key: String): Long = Files.size(resolve(key))

    fun getObject(key: String): InputStream = Files.newInputStream(resolve(key))

    /**
     * The object as a Spring [FileSystemResource]: streamed by MVC directly
     * (Content-Length taken from file attributes by the framework).
     */
    fun getResource(key: String): FileSystemResource = FileSystemResource(resolve(key))

    /**
     * Download-time integrity check: re-hashes the stored bytes and compares
     * against the expected hash (case-insensitively).
     */
    fun verifyHash(key: String, expectedHash: String): Boolean {
        return try {
            val actual = getObject(key).use { DigestUtils.sha256Hex(it) }
            actual.equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            logger.warn("Hash verification read failed for {}: {}", key, e.message)
            false
        }
    }

    fun deleteObject(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    /**
     * Deletes matching originals under `documents/{hash}.*` using OS-level glob filtering.
     */
    fun deleteObjectsByHashPrefix(hash: String) {
        val dir = root.resolve(DOCUMENTS_PREFIX)
        if (!Files.isDirectory(dir)) return
        Files.newDirectoryStream(dir, "$hash.*").use { stream ->
            stream.forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Recursively deletes the claim-check scratch directory `scratch/doc-{hash}`.
     */
    fun deleteScratch(hash: String) {
        val dir = resolve("$SCRATCH_PREFIX/doc-$hash")
        FileSystemUtils.deleteRecursively(dir)
    }

    /**
     * Lists all files under a prefix in a single filesystem pass (avoiding double-stat syscalls).
     */
    fun listPrefix(prefix: String): List<StoredFile> {
        val start = resolve(prefix)
        if (!Files.isDirectory(start)) return emptyList()

        val result = mutableListOf<StoredFile>()
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).toString().replace('\\', '/')
                result.add(StoredFile(relative, attrs.size(), attrs.lastModifiedTime().toInstant()))
                return FileVisitResult.CONTINUE
            }
        })
        return result
    }

    // ------------------------------------------------------------ scratch text

    fun writeText(key: String, text: String) {
        put(key, text.byteInputStream())
    }

    fun readText(key: String): String? {
        val target = resolve(key)
        return if (Files.isRegularFile(target)) Files.readString(target) else null
    }

    // ------------------------------------------------------------------ utils

    private fun resolve(key: String): Path {
        // Strip leading slashes to prevent Path.resolve() treating key as an absolute root path
        val sanitized = key.trimStart('/', '\\')
        val p = root.resolve(sanitized).normalize()
        require(p.startsWith(root)) { "Illegal storage key: $key" }
        return p
    }
}