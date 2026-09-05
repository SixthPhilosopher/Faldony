package com.kiss.backend.util

import com.kiss.backend.model.entity.DocumentType
import org.apache.tika.Tika
import java.io.InputStream

/**
 * MIME handling for uploads and downstream processing.
 *
 * ## Content vs filename trust
 * CONTENT is the only source of truth for a file's type (Tika magic-byte
 * detection). The filename is trusted in exactly ONE place: OOXML packages
 * sniff as generic `application/zip`, so the extension disambiguates
 * docx/xlsx/pptx. A garbage file renamed `.pdf` still fails (its content stays
 * plain binary / octet-stream).
 *
 * ## Single source of truth
 * `SUPPORTED` drives everything: the upload allow-list
 * ([isSupportedUploadMimeType]), the document type (PDF/IMAGE/DOCUMENT)
 * persisted and used for filtering ([documentTypeFor]), and the extension hint
 * handed to docling for detection ([doclingDetectionExtension]). A new
 * supported mime is added once — the derived functions stay consistent by
 * construction.
 */
object MimeTypes {

    private val tika = Tika()

    /**
     * Mime type -> (document type, docling detection extension).
     * `text/` is a PREFIX entry: it matches any text subtype.
     */
    private val SUPPORTED: Map<String, Pair<DocumentType, String>> = mapOf(
        "application/pdf" to (DocumentType.PDF to "pdf"),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to (DocumentType.DOCUMENT to "docx"),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to (DocumentType.DOCUMENT to "xlsx"),
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to (DocumentType.DOCUMENT to "pptx"),
        "text/" to (DocumentType.DOCUMENT to "txt"),
        "image/jpeg" to (DocumentType.IMAGE to "jpg"),
        "image/png" to (DocumentType.IMAGE to "png"),
        "image/tiff" to (DocumentType.IMAGE to "tiff"),
        "image/bmp" to (DocumentType.IMAGE to "bmp"),
        "image/webp" to (DocumentType.IMAGE to "webp"),
        "image/gif" to (DocumentType.IMAGE to "gif")
    )

    /** Exact mime types (the prefix entry `text/` is matched per-prefix). */
    private val EXACT_MIMES: Set<String> = SUPPORTED.keys.filter { !it.endsWith("/") }.toSet()
    private val PREFIX_MIMES: List<Pair<String, Pair<DocumentType, String>>> =
        SUPPORTED.entries.filter { it.key.endsWith("/") }.map { it.key to it.value }

    /**
     * Upload allow-list gate (DocumentService.uploadDocument → 415).
     * True when the mime type is one of the accepted upload types.
     */
    fun isSupportedUploadMimeType(mimeType: String): Boolean {
        val lower = mimeType.lowercase()
        return lower in EXACT_MIMES || PREFIX_MIMES.any { lower.startsWith(it.first) }
    }

    /**
     * Maps a mime type to its [DocumentType] bucket (PDF/IMAGE/DOCUMENT) —
     * used by the document entity (finalize) and the processing-queue memo.
     * Unknown/unsupported types fall back to DOCUMENT.
     */
    fun documentTypeFor(mimeType: String): DocumentType =
        infoFor(mimeType)?.first ?: DocumentType.DOCUMENT

    /**
     * Extension for the upload object key (`documents/{hash}.{ext}`,
     * DocumentService.uploadDocument): taken from the ORIGINAL upload name,
     * stripped of anything that isn't alphanumeric, max 10 chars (sanitized
     * so a hostile filename cannot shape the storage key).
     */
    fun safeObjectKeyExtension(fileName: String?): String? {
        val raw = fileName?.substringAfterLast('.', "")?.lowercase() ?: return null
        val cleaned = raw.filter { it.isLetterOrDigit() }.take(10)
        return cleaned.ifBlank { null }
    }

    /**
     * Extension hint for docling's file detection (DocumentActivities
     * `extractAndChunk` → `${hash}.${ext}`): the mime type is canonical, the
     * extension is what docling's own sniffing wants to see.
     */
    fun doclingDetectionExtension(mimeType: String): String =
        infoFor(mimeType)?.second ?: "txt"

    /**
     * Sniffs the CONTENT of an uploaded file (magic bytes via Tika) and
     * returns the canonical mime type, or null when the content is not a
     * recognized/supported type. Entry gate of DocumentService.uploadDocument.
     *
     * The filename is trusted in exactly ONE place: OOXML packages sniff as
     * generic `application/zip`, so the extension disambiguates docx/xlsx/pptx;
     * similarly, unclassified text (octet-stream/plain) is pinned to a few
     * text flavors by extension (md/csv/html).
     */
    fun sniffUploadMimeType(stream: InputStream, fileName: String? = null): String? {
        // CONTENT is the only source of truth (see class doc).
        val detected = runCatching { tika.detect(stream) }.getOrNull() ?: return null
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()

        if (detected == "application/zip") {
            return OOXML_BY_EXT[ext ?: ""]?.takeIf { isSupportedUploadMimeType(it) }
        }

        if (isSupportedUploadMimeType(detected)) {
            return detected
        }

        // Whatever Tika could not classify may still be one of the handful of
        // text flavors we accept, disambiguated by extension only.
        return if (detected == "application/octet-stream" || detected.startsWith("text/")) {
            when (ext) {
                "md", "markdown" -> "text/markdown"
                "csv" -> "text/csv"
                "html", "htm" -> "text/html"
                else -> null
            }
        } else {
            null
        }
    }

    private fun infoFor(mimeType: String): Pair<DocumentType, String>? {
        val lower = mimeType.lowercase()
        SUPPORTED[lower]?.let { return it }
        return PREFIX_MIMES.firstOrNull { lower.startsWith(it.first) }?.second
    }

    /** OOXML packages sniff as zip; the extension disambiguates them. */
    private val OOXML_BY_EXT = mapOf(
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )
}