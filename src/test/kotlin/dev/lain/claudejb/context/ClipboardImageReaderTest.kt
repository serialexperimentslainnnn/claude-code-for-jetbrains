package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Pure tests for [ClipboardImageReader]: the file-list path, both raw `image/…` paths (the Linux fix —
 * InputStream- and byte[]-backed flavors), and the negative/throwing cases. No real clipboard — synthetic
 * [Transferable]s only, so it runs headlessly.
 */
class ClipboardImageReaderTest {

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)

    /** Minimal single-flavor [Transferable] returning [data] (or throwing when null) for [flavor]. */
    private class SingleFlavor(private val flavor: DataFlavor, private val data: () -> Any) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(flavor)
        override fun isDataFlavorSupported(f: DataFlavor): Boolean = f == flavor
        override fun getTransferData(f: DataFlavor): Any =
            if (f == flavor) data() else throw UnsupportedFlavorException(f)
    }

    /**
     * An `image/png` flavor whose representation class is forced to [repr]. The 2-arg [DataFlavor] constructor
     * defaults to [InputStream]; for the `byte[]` case we override [DataFlavor.getRepresentationClass].
     */
    private fun imageFlavor(repr: Class<*>): DataFlavor =
        object : DataFlavor("image/png", "PNG Image") {
            override fun getRepresentationClass(): Class<*> = repr
        }

    @Test
    fun `file-list with an image file returns its bytes and name`() {
        val tmp = File.createTempFile("clip", ".png").apply { writeBytes(pngBytes); deleteOnExit() }
        val t = SingleFlavor(DataFlavor.javaFileListFlavor) { listOf(tmp) }

        val result = ClipboardImageReader.readImageBytes(t)
        assertNotNull(result)
        assertTrue(pngBytes.contentEquals(result!!.bytes))
        assertTrue(result.suggestedName!!.endsWith(".png"))
        assertTrue(ClipboardImageReader.hasImage(t))
    }

    @Test
    fun `image flavor backed by InputStream returns bytes (Linux case)`() {
        val flavor = imageFlavor(InputStream::class.java)
        val t = SingleFlavor(flavor) { ByteArrayInputStream(pngBytes) }

        val result = ClipboardImageReader.readImageBytes(t)
        assertNotNull(result)
        assertTrue(pngBytes.contentEquals(result!!.bytes))
        assertTrue(ClipboardImageReader.hasImage(t))
    }

    @Test
    fun `image flavor backed by byte array returns bytes`() {
        val flavor = imageFlavor(ByteArray::class.java)
        val t = SingleFlavor(flavor) { pngBytes }

        val result = ClipboardImageReader.readImageBytes(t)
        assertNotNull(result)
        assertTrue(pngBytes.contentEquals(result!!.bytes))
    }

    @Test
    fun `string-only transferable yields no image`() {
        val t = StringSelection("just text")
        assertNull(ClipboardImageReader.readImageBytes(t))
        assertFalse(ClipboardImageReader.hasImage(t))
    }

    @Test
    fun `throwing getTransferData is swallowed`() {
        val flavor = imageFlavor(InputStream::class.java)
        val t = SingleFlavor(flavor) { error("boom") }
        assertNull(ClipboardImageReader.readImageBytes(t))
    }
}
