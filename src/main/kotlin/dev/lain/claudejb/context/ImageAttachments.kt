package dev.lain.claudejb.context

import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Turns an image payload — raw clipboard bytes, a rendered AWT image, a file on disk, or a base64 blob the
 * web app handed the bridge — into an [Attachment.Image] (base64 + IANA media type).
 *
 * The last of those, [fromWebPayload], is the only one that is a **trust boundary**: everything else is bytes
 * this process read itself, while `{type:'attach'}` carries a size, a name and a media type chosen by the
 * renderer. It is validated accordingly; the rest are not, and must not be handed renderer input.
 *
 * IDE-free and project-free (no Project, no editor, no clipboard access), so the media-type mapping and the
 * file reader are unit-testable on a plain JVM. Every entry point confines its failures and answers null
 * rather than throwing: an unreadable file or an unsupported type is "no attachment", never an exception.
 */
internal object ImageAttachments {

    /** Floor for a clipboard payload to be a plausible image: the longest magic-byte signature (PNG) is 8 bytes. */
    private const val MIN_IMAGE_BYTES = 8

    /**
     * Ceiling for an image arriving from the **web layer** (drag&drop / paste in the composer). Larger payloads
     * are refused so a stray huge file cannot bloat a turn — or, long before the model sees it, exhaust the heap:
     * the base64 string, the decoded array and the JSON stdin line are three live copies of the same bytes.
     */
    const val MAX_IMAGE_BYTES: Int = 8 * 1024 * 1024

    /** Longest base64 string that can decode within [MAX_IMAGE_BYTES] (4 chars per 3 bytes, plus padding). */
    private const val MAX_BASE64_CHARS: Int = (MAX_IMAGE_BYTES + 2) / 3 * 4 + 4

    /** Longest display name we keep: enough for any real file name, short enough not to be a payload itself. */
    private const val MAX_DISPLAY_NAME = 128

    /**
     * Builds an [Attachment.Image] from a payload the **web app** supplied (`{type:'attach'}`), or null when it
     * is not an image we accept. This is a trust boundary, not a convenience: `name`, `mediaType` and `base64`
     * all come from the renderer — `mediaType` is whatever the browser guessed from the file's extension, so a
     * renamed executable arrives announced as `image/png`.
     *
     * Therefore the declared [mediaType] is **deliberately not read** here: the type that reaches the wire is
     * sniffed from the decoded bytes, and must be one the model accepts as an image content block
     * (png/jpeg/gif/webp). The base64 is re-encoded from those same bytes rather than passed through, so what is
     * sent is exactly what was validated. The size is capped twice — on the encoded string first, so an
     * oversized payload is refused *before* it is decoded into a second copy.
     *
     * [mediaType] stays in the signature because the **caller** needs it: on a null return it is what lets the
     * composer tray say "you dropped something that announces itself as `image/png` and is not one" instead of
     * a chip silently failing to appear. Validating against it here is what would be wrong, not accepting it.
     */
    // UnusedParameter is correct about the body and wrong about the design: reading the caller's declared type
    // would be exactly the bug this function exists to prevent. It is the rejection message's input, not ours.
    @Suppress("UnusedParameter")
    fun fromWebPayload(name: String, mediaType: String, base64: String): Attachment.Image? {
        val payload = base64.filterNot { it.isWhitespace() }
        if (payload.isEmpty() || payload.length > MAX_BASE64_CHARS) return null
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        if (bytes.size < MIN_IMAGE_BYTES || bytes.size > MAX_IMAGE_BYTES) return null
        // Sniffed, never declared: `mediaType` is the renderer's claim about the bytes, not a fact about them.
        val sniffed = sniffMediaType(bytes) ?: return null
        return Attachment.Image(
            displayName = displayNameOf(name, sniffed),
            mediaType = sniffed,
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }

    /**
     * A safe chip label for [name]: the basename (both separators — the payload may name a Windows path), with
     * control characters dropped and the length bounded. Falls back to `image.<ext>` when nothing usable remains.
     */
    private fun displayNameOf(name: String, mediaType: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() }
            .trim()
            .take(MAX_DISPLAY_NAME)
        return base.ifBlank { "image." + mediaType.substringAfter('/').substringBefore('+') }
    }

    /** Decodes a signature written the way format specs and `file(1)` magic databases write one: as hex. */
    private fun signature(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Leading magic bytes of each accepted format. Sniffing beats trusting the name or the declared type: both
    // are supplied by whoever hands us the payload, and the bytes are the only thing that is not a claim.
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

    /**
     * Detects the image type from the leading magic bytes; null when unrecognized. The recognized set is exactly
     * what the model accepts as an image content block, so anything else is refused here rather than downstream.
     */
    fun sniffMediaType(b: ByteArray): String? = when {
        b.hasSignature(PNG_SIGNATURE) -> "image/png"
        b.hasSignature(JPEG_SIGNATURE) -> "image/jpeg"
        b.hasSignature(GIF_SIGNATURE) -> "image/gif"
        b.hasSignature(RIFF_SIGNATURE) && b.hasSignature(WEBP_FORM_TYPE, RIFF_FORM_TYPE_OFFSET) -> "image/webp"
        else -> null
    }

    /** Wraps raw clipboard [bytes] advertised as [type], normalizing `image/jpg` and deriving the file name. */
    fun imageOf(bytes: ByteArray, type: String): Attachment.Image? {
        // Shorter than the longest magic-byte signature we could match, so it cannot be an image we accept.
        if (bytes.size < MIN_IMAGE_BYTES) return null
        val mt = if (type == "image/jpg") "image/jpeg" else type
        val ext = mt.substringAfter('/').substringBefore('+').ifBlank { "png" }
        return Attachment.Image("clipboard.$ext", mt, Base64.getEncoder().encodeToString(bytes))
    }

    /** Reads an image file from disk, detecting media type by extension, as an [Attachment.Image], or null. */
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

    /** Maps an image file extension to its IANA media type, or null when not a supported image. */
    fun mediaTypeForExtension(ext: String): String? = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }

    /** Encodes an AWT image (the shape the system clipboard hands back) as base64 PNG, or null. */
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
