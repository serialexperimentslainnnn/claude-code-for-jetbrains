package dev.lain.claudejb.context

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.io.InputStream

/**
 * Pure, AWT-only (no IDE/Application services) extraction of **raw image bytes** from a [Transferable], so the
 * composer's paste/drop path can attach a clipboard image. Centralizes the Linux clipboard quirk (Wayland over XWayland behaves like X11): there an image
 * arrives as a flavor with `primaryType == "image"` carrying an [InputStream] or `byte[]` representation — **not**
 * [DataFlavor.imageFlavor] (a rendered [java.awt.Image]). Only the raw-bytes paths live here; the rendered-image
 * fallback stays in the UI layer.
 *
 * Media-type sniffing and the size cap are delegated to [AttachmentEncoder]; this object only locates and reads
 * the payload, leaving validation to the caller (which feeds the bytes to [AttachmentEncoder.fromBytes]).
 */
object ClipboardImageReader {

    /** Raw image payload plus an optional name hint (e.g. a file name from a file-list flavor). */
    data class ImageBytes(val bytes: ByteArray, val suggestedName: String?) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ImageBytes && bytes.contentEquals(other.bytes) && suggestedName == other.suggestedName)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + (suggestedName?.hashCode() ?: 0)
    }

    /**
     * Reads raw image bytes from [t], in priority order:
     *  1. [DataFlavor.javaFileListFlavor] — the first file whose content sniffs as an image (name hint = file name).
     *  2. Any flavor with `primaryType == "image"` backed by an [InputStream] or `byte[]` (the Linux fix).
     *
     * Returns the first source that yields non-empty bytes, or null when nothing usable is present. A flavor whose
     * `getTransferData` throws (or whose bytes are empty) is skipped, never propagated.
     */
    fun readImageBytes(t: Transferable): ImageBytes? {
        readFromFileList(t)?.let { return it }
        return readFromImageFlavor(t)
    }

    /**
     * Cheap presence check: true when [t] offers a file-list (assumed to possibly hold an image) or any raw
     * `image/…` flavor. Does not fully read the payload, so a positive answer is only a likelihood.
     */
    fun hasImage(t: Transferable): Boolean {
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true
        return t.transferDataFlavors.any { it.isRawImageFlavor() }
    }

    /** Reads the first file in a file-list whose bytes sniff as an image; null if absent or none qualify. */
    private fun readFromFileList(t: Transferable): ImageBytes? {
        if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
        val files = runCatching { t.getTransferData(DataFlavor.javaFileListFlavor) }.getOrNull() as? List<*> ?: return null
        for (entry in files) {
            val file = entry as? File ?: continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.isNotEmpty() && AttachmentEncoder.sniffMediaType(bytes) != null) {
                return ImageBytes(bytes, file.name)
            }
        }
        return null
    }

    /** Reads the first raw `image/…` flavor backed by an InputStream or byte[]; the Linux (Wayland/X11) clipboard-image path. */
    private fun readFromImageFlavor(t: Transferable): ImageBytes? {
        for (flavor in t.transferDataFlavors) {
            if (!flavor.isRawImageFlavor()) continue
            val bytes = runCatching {
                when (val data = t.getTransferData(flavor)) {
                    is InputStream -> data.use { it.readBytes() }
                    is ByteArray -> data
                    else -> null
                }
            }.getOrNull() ?: continue
            if (bytes.isNotEmpty()) {
                return ImageBytes(bytes, flavor.subType?.takeIf { it.isNotBlank() })
            }
        }
        return null
    }

    /** True for an `image/…` flavor whose representation is raw bytes (InputStream or byte[]), not a rendered Image. */
    private fun DataFlavor.isRawImageFlavor(): Boolean =
        primaryType == "image" &&
            (representationClass == InputStream::class.java || representationClass == ByteArray::class.java)
}
