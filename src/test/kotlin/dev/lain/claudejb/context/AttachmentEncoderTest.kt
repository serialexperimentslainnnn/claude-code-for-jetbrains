package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Pure tests for [AttachmentEncoder]: magic-byte sniffing, extension fallback, the supported-type gate and the
 * size guard. No AWT/Swing — the bytes-in / [Attachment.Image]-out contract the composer's drop/paste relies on.
 */
class AttachmentEncoderTest {

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
    private val gifMagic = "GIF89a".toByteArray()

    @Test
    fun `sniffs png from magic bytes`() {
        assertEquals("image/png", AttachmentEncoder.sniffMediaType(pngMagic))
    }

    @Test
    fun `sniffs jpeg and gif from magic bytes`() {
        assertEquals("image/jpeg", AttachmentEncoder.sniffMediaType(jpegMagic))
        assertEquals("image/gif", AttachmentEncoder.sniffMediaType(gifMagic))
    }

    @Test
    fun `sniffs webp from RIFF____WEBP header`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertEquals("image/webp", AttachmentEncoder.sniffMediaType(webp))
    }

    @Test
    fun `unknown bytes do not sniff`() {
        assertNull(AttachmentEncoder.sniffMediaType("not an image".toByteArray()))
    }

    @Test
    fun `mediaTypeFromName maps known extensions`() {
        assertEquals("image/png", AttachmentEncoder.mediaTypeFromName("a/b/c.PNG"))
        assertEquals("image/jpeg", AttachmentEncoder.mediaTypeFromName("photo.jpeg"))
        assertNull(AttachmentEncoder.mediaTypeFromName("notes.txt"))
    }

    @Test
    fun `fromBytes builds a base64 Image with sniffed type and basename display`() {
        val img = AttachmentEncoder.fromBytes("dir/shot.png", pngMagic)!!
        assertEquals("image/png", img.mediaType)
        assertEquals("shot.png", img.displayName)
        assertEquals(pngMagic.toList(), Base64.getDecoder().decode(img.base64).toList())
    }

    @Test
    fun `fromBytes rejects empty, oversized and non-image payloads`() {
        assertNull(AttachmentEncoder.fromBytes("x.png", ByteArray(0)))
        assertNull(AttachmentEncoder.fromBytes("x.png", ByteArray(AttachmentEncoder.MAX_IMAGE_BYTES + 1) { 0x89.toByte() }))
        assertNull(AttachmentEncoder.fromBytes("x.txt", "hello".toByteArray()))
    }
}
