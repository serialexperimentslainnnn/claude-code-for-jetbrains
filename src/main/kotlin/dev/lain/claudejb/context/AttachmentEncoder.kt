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

    /** Decodes a signature written the way format specs and `file(1)` magic databases write one: as hex. */
    private fun signature(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Leading magic bytes of each accepted format. Sniffing beats trusting the name: the composer also takes
    // clipboard payloads, which arrive with no filename at all.
    private val PNG_SIGNATURE = signature("89504E470D0A1A0A")
    private val JPEG_SIGNATURE = signature("FFD8FF")
    private val GIF_SIGNATURE = "GIF".toByteArray(Charsets.US_ASCII)

    // WEBP is a RIFF container, so "RIFF" alone identifies nothing (WAV and AVI share it). What names the
    // format is the four-byte FORM TYPE that follows the header and its 4-byte payload length.
    private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_FORM_TYPE = "WEBP".toByteArray(Charsets.US_ASCII)
    private const val RIFF_FORM_TYPE_OFFSET = 8

    /** True when this array carries [signature] at [offset], bounds-checked. */
    private fun ByteArray.hasSignature(signature: ByteArray, offset: Int = 0): Boolean =
        size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

    /** Detects the image type from the leading magic bytes; null when unrecognized. */
    fun sniffMediaType(b: ByteArray): String? = when {
        b.hasSignature(PNG_SIGNATURE) -> "image/png"
        b.hasSignature(JPEG_SIGNATURE) -> "image/jpeg"
        b.hasSignature(GIF_SIGNATURE) -> "image/gif"
        b.hasSignature(RIFF_SIGNATURE) && b.hasSignature(WEBP_FORM_TYPE, RIFF_FORM_TYPE_OFFSET) -> "image/webp"
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
