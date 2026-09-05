package org.kiss.data

import io.github.vinceglb.filekit.PlatformFile

/**
 * Platform file primitives: picking a local file (JS: browser <input type=file>)
 * and saving downloaded bytes. Common contract, per-platform actuals.
 */
expect class PickedFile(name: String, mimeType: String, bytes: ByteArray) {
    val name: String
    val mimeType: String
    val bytes: ByteArray
}

/** Selects a local file through the FileKit launcher (see DocumentsScreen). */

/**
 * Reads a picked file's bytes OFF the main thread. FileKit's own
 * `PlatformFile.readBytes()` is hardcoded to `Dispatchers.Main`, which starves
 * on the Compose web main loop; the platform actual must run the browser
 * FileReader on a background dispatcher instead.
 */
expect suspend fun readPickedFileBytes(file: PlatformFile): ByteArray

/** Hands [bytes] to the platform save dialog (browser download). */
expect fun downloadBytes(fileName: String, bytes: ByteArray, mimeType: String)

/**
 * Renders a previewable blob (image or PDF) in a DOM overlay anchored to the
 * right edge at the given pixel width (the details drawer's panel). Returns a
 * close function (idempotent).
 */
expect fun openDomPreview(bytes: ByteArray, mimeType: String, widthPx: Int, onClose: () -> Unit): () -> Unit

/** Opens a previewable blob (image or PDF) in a new browser tab. */
expect fun openBlobTab(bytes: ByteArray, mimeType: String)