package dev.lain.claudejb.context

import java.util.Base64

/**
 * Pure helpers for turning raw image bytes into an [Attachment.Image] (base64 + IANA media type). Kept free of
 * Swing/AWT so the encoding, media-type detection and size guard are unit-testable; the composer's drag&drop /
 * paste handler reads the bytes and calls [fromBytes].
 */
object AttachmentEncoder {

    /** Max accepted image size (bytes). Larger payloads are rejected so a stray huge file can't bloat the turn. */
    const val MAX_IMAGE_BYTES: Int = 8 * 1024 * 1024

    /** IANA media types we accept; the binary forwards these to the model as image content blocks. */
    private val SUPPORTED = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /**
     * Builds an [Attachment.Image] from [bytes], inferring the media type from the magic bytes (falling back to the
     * extension of [name]). Returns null when the payload is empty, too large, or not a recognized image type.
     */
    fun fromBytes(name: String, bytes: ByteArray): Attachment.Image? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val mediaType = sniffMediaType(bytes) ?: mediaTypeFromName(name) ?: return null
        if (mediaType !in SUPPORTED) return null
        val display = name.substringAfterLast('/').ifBlank { "image" }
        return Attachment.Image(display, mediaType, Base64.getEncoder().encodeToString(bytes))
    }

    /** Detects the image type from the leading magic bytes; null when unrecognized. */
    fun sniffMediaType(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "image/webp"
        else -> null
    }

    /** Maps a filename extension to a media type, for paths whose bytes don't sniff (rare). */
    fun mediaTypeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }
}
