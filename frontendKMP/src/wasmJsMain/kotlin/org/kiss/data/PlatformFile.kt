@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package org.kiss.data

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.files.Blob
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.js.unsafeCast

actual class PickedFile actual constructor(
    name: String,
    mimeType: String,
    bytes: ByteArray,
) {
    actual val name: String = name
    actual val mimeType: String = mimeType
    actual val bytes: ByteArray = bytes
}

private val FILE_EXTENSIONS = listOf("pdf", "doc", "docx", "txt", "png", "jpg", "jpeg", "tiff", "bmp", "webp", "gif")

// ---------------------------------------------------------------------------
// Typed JS-expression helpers: in Kotlin/Wasm, `js(code)` may only appear as
// the single expression of a top-level function body (or a property
// initializer), and interop function/value-parameter types are restricted to
// external, primitive, string and function types — so every JS escape goes
// through these one-liners and the rest of the file is ordinary Kotlin.
// ---------------------------------------------------------------------------

private fun blob(base64: String, mimeType: String): Blob =
    js("new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: mimeType })")

private fun blobFor(bytes: ByteArray, mimeType: String): Blob =
    blob(Base64.Default.encode(bytes), mimeType)

private fun createObjectUrl(blob: Blob): String = js("URL.createObjectURL(blob)")

private fun revokeObjectUrl(url: String): Unit = js("URL.revokeObjectURL(url)")

private fun openBlank(url: String): Unit = js("window.open(url, '_blank')")

// ---- file picker -------------------------------------------------------------
// File selection is delegated to FileKit's Compose launcher
// (rememberFilePickerLauncher in filekit-dialogs-compose) — see
// DocumentsScreen. The old hand-rolled picker actual was removed.

/**
 * Reads a picked browser file OFF the main thread.
 *
 * FileKit's own `readBytes()` hardcodes `Dispatchers.Main` and the byte-copy
 * loop, which starves on the Compose web main loop (observed ~1 min latency
 * before the file appears). Here the FileReader runs on `Dispatchers.Default`;
 * only the tiny buffer-to-ByteArray copy hops back to main-free code.
 */
actual suspend fun readPickedFileBytes(file: PlatformFile): ByteArray =
    withContext(Dispatchers.Default) {
        val wrapper = file.webFile as? WebFile.FileWrapper
            ?: throw IllegalStateException("Picked file is not a regular file")
        suspendCancellableCoroutine { continuation ->
            val reader = FileReader()
            reader.onload = { _: Event ->
                try {
                    val buffer = reader.result!!.unsafeCast<ArrayBuffer>()
                    val u8 = Uint8Array(buffer)
                    val bytes = ByteArray(u8.length)
                    for (i in 0 until u8.length) bytes[i] = u8[i]
                    continuation.resume(bytes)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
            reader.onerror = { _: Event ->
                continuation.resumeWithException(
                    IllegalStateException("FileReader failed for ${wrapper.name}")
                )
            }
            reader.readAsArrayBuffer(wrapper.file)
        }
    }

/**
 * Downloads via a real Blob + object URL with the correct MIME type and an
 * anchor attached to the DOM. The previous base64 data-URL blew up on large
 * files (browser URL-length limits) and the hardcoded ".pdf" name corrupted
 * every non-PDF document.
 */
actual fun downloadBytes(fileName: String, bytes: ByteArray, mimeType: String) {
    val safeName = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_")
    val url = createObjectUrl(blobFor(bytes, mimeType))
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.download = safeName
    document.body?.appendChild(anchor)
    anchor.click()
    document.body?.removeChild(anchor)
    window.setTimeout({ revokeObjectUrl(url); null }, 10000)
}

/**
 * Previewable blob (image or PDF) rendered in a DOM overlay: a scrim plus a
 * right-anchored panel the same width as the details drawer. The Compose
 * canvas cannot host <img>/<iframe> from a blob URL, so the overlay sits
 * above it and is removed when the drawer closes.
 */
actual fun openDomPreview(bytes: ByteArray, mimeType: String, widthPx: Int, onClose: () -> Unit): () -> Unit {
    val url = createObjectUrl(blobFor(bytes, mimeType))

    val container = document.createElement("div") as HTMLDivElement
    container.style.cssText =
        "position:fixed;inset:0;z-index:2147483647;background:rgba(0,0,0,0.45);display:flex;justify-content:flex-end;align-items:stretch;"
    val panel = document.createElement("div") as HTMLDivElement
    panel.style.cssText =
        "position:relative;width:${widthPx}px;max-width:100%;height:100%;background:#fff;display:flex;flex-direction:column;"
    val media: Node = if (mimeType == "application/pdf") {
        val f = document.createElement("iframe") as HTMLIFrameElement
        f.style.cssText = "flex:1;border:0;width:100%;height:100%;"
        f.src = url
        f
    } else {
        val img = document.createElement("img") as HTMLImageElement
        img.style.cssText =
            "flex:1;border:0;width:100%;height:100%;object-fit:contain;background:#fff;"
        img.src = url
        img
    }
    panel.appendChild(media)

    val closeButton = document.createElement("button") as HTMLButtonElement
    closeButton.textContent = "\u2715"
    closeButton.style.cssText =
        "position:absolute;top:10px;right:14px;z-index:2;background:rgba(0,0,0,0.55);color:#fff;border:none;" +
            "border-radius:50%;width:34px;height:34px;font-size:18px;cursor:pointer;"
    panel.appendChild(closeButton)
    container.appendChild(panel)

    fun close() {
        if (container.parentNode != null) {
            container.parentNode?.removeChild(container)
        }
        revokeObjectUrl(url)
        onClose()
    }
    closeButton.onclick = { _: Event -> close() }
    container.onclick = { e: Event ->
        if (e.target == container) close()
    }
    document.body?.appendChild(container)
    return { close() }
}

actual fun openBlobTab(bytes: ByteArray, mimeType: String) {
    val url = createObjectUrl(blobFor(bytes, mimeType))
    openBlank(url)
    window.setTimeout({ revokeObjectUrl(url); null }, 60000)
}