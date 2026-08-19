package dev.lain.claudejb.context

import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

internal object ImageAttachments {

    private const val MIN_IMAGE_BYTES = 8

    const val MAX_IMAGE_BYTES: Int = 8 * 1024 * 1024

    private const val MAX_BASE64_CHARS: Int = (MAX_IMAGE_BYTES + 2) / 3 * 4 + 4

    private const val MAX_DISPLAY_NAME = 128

    @Suppress("UnusedParameter")
    fun fromWebPayload(name: String, mediaType: String, base64: String): Attachment.Image? {
        val payload = base64.filterNot { it.isWhitespace() }
        if (payload.isEmpty() || payload.length > MAX_BASE64_CHARS) return null
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        if (bytes.size < MIN_IMAGE_BYTES || bytes.size > MAX_IMAGE_BYTES) return null
        val sniffed = sniffMediaType(bytes) ?: return null
        return Attachment.Image(
            displayName = displayNameOf(name, sniffed),
            mediaType = sniffed,
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun displayNameOf(name: String, mediaType: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() }
            .trim()
            .take(MAX_DISPLAY_NAME)
        return base.ifBlank { "image." + mediaType.substringAfter('/').substringBefore('+') }
    }

    private fun signature(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val PNG_SIGNATURE = signature("89504E470D0A1A0A")
    private val JPEG_SIGNATURE = signature("FFD8FF")
    private val GIF_SIGNATURE = "GIF".toByteArray(Charsets.US_ASCII)

    private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_FORM_TYPE = "WEBP".toByteArray(Charsets.US_ASCII)
    private const val RIFF_FORM_TYPE_OFFSET = 8

    private fun ByteArray.hasSignature(signature: ByteArray, offset: Int = 0): Boolean =
        size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

    fun sniffMediaType(b: ByteArray): String? = when {
        b.hasSignature(PNG_SIGNATURE) -> "image/png"
        b.hasSignature(JPEG_SIGNATURE) -> "image/jpeg"
        b.hasSignature(GIF_SIGNATURE) -> "image/gif"
        b.hasSignature(RIFF_SIGNATURE) && b.hasSignature(WEBP_FORM_TYPE, RIFF_FORM_TYPE_OFFSET) -> "image/webp"
        else -> null
    }

    fun imageOf(bytes: ByteArray, type: String): Attachment.Image? {
        if (bytes.size < MIN_IMAGE_BYTES) return null
        val mt = if (type == "image/jpg") "image/jpeg" else type
        val ext = mt.substringAfter('/').substringBefore('+').ifBlank { "png" }
        return Attachment.Image("clipboard.$ext", mt, Base64.getEncoder().encodeToString(bytes))
    }

    fun imageFromFile(path: String): Attachment.Image? = runCatching {
        val file = File(path)
        val bytes = file.takeIf { it.isFile }?.readBytes() ?: return null
        if (bytes.isEmpty()) return null
        val mediaType = mediaTypeForExtension(file.extension.lowercase()) ?: return null
        Attachment.Image(
            displayName = file.name,
            mediaType = mediaType,
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }.getOrNull()

    fun mediaTypeForExtension(ext: String): String? = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }

    fun pngBase64(image: java.awt.Image): String? = runCatching {
        val rendered = image.toRenderedImage() ?: return null
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(rendered, "png", out)) return null
        Base64.getEncoder().encodeToString(out.toByteArray())
    }.getOrNull()

    private fun java.awt.Image.toRenderedImage(): RenderedImage? {
        (this as? RenderedImage)?.let { return it }
        val width = getWidth(null)
        val height = getHeight(null)
        if (width <= 0 || height <= 0) return null
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        try {
            g.drawImage(this, 0, 0, null)
        } finally {
            g.dispose()
        }
        return buffered
    }
}
