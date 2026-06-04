package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Pure-JVM coverage of the project-free helpers on [EditorContextProvider]: the extension→lang and
 * extension→media-type maps, plus [EditorContextProvider.imageFromFile] reading a real on-disk PNG
 * and encoding it to non-empty base64. Editor/clipboard accessors need a Project/EDT and are
 * exercised in the headless suite instead.
 */
class EditorContextProviderImageTest {

    @Test
    fun `langForExtension maps known extensions`() {
        assertEquals("kotlin", EditorContextProvider.langForExtension("kt"))
        assertEquals("python", EditorContextProvider.langForExtension("py"))
        assertEquals("typescript", EditorContextProvider.langForExtension("ts"))
        assertEquals("bash", EditorContextProvider.langForExtension("sh"))
    }

    @Test
    fun `langForExtension returns null for unknown`() {
        assertNull(EditorContextProvider.langForExtension("xyz"))
        assertNull(EditorContextProvider.langForExtension(""))
    }

    @Test
    fun `mediaTypeForExtension maps image extensions`() {
        assertEquals("image/png", EditorContextProvider.mediaTypeForExtension("png"))
        assertEquals("image/jpeg", EditorContextProvider.mediaTypeForExtension("jpg"))
        assertEquals("image/jpeg", EditorContextProvider.mediaTypeForExtension("jpeg"))
        assertEquals("image/gif", EditorContextProvider.mediaTypeForExtension("gif"))
        assertEquals("image/webp", EditorContextProvider.mediaTypeForExtension("webp"))
    }

    @Test
    fun `mediaTypeForExtension returns null for non-images`() {
        assertNull(EditorContextProvider.mediaTypeForExtension("txt"))
        assertNull(EditorContextProvider.mediaTypeForExtension(""))
    }

    @Test
    fun `imageFromFile reads a PNG and base64-encodes it`(@TempDir dir: Path) {
        val png = File(dir.toFile(), "pic.png")
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFF0000.toInt())
        ImageIO.write(image, "png", png)

        val attachment = EditorContextProvider.imageFromFile(png.absolutePath)
        requireNotNull(attachment) { "expected a non-null Image attachment" }
        assertEquals("pic.png", attachment.displayName)
        assertEquals("image/png", attachment.mediaType)
        assertTrue(attachment.base64.isNotEmpty(), "base64 must not be empty")
        // round-trips to the on-disk bytes
        assertTrue(Base64.getDecoder().decode(attachment.base64).contentEquals(png.readBytes()))
    }

    @Test
    fun `imageFromFile returns null for a missing file`(@TempDir dir: Path) {
        assertNull(EditorContextProvider.imageFromFile(File(dir.toFile(), "nope.png").absolutePath))
    }

    @Test
    fun `imageFromFile returns null for an unsupported extension`(@TempDir dir: Path) {
        val txt = File(dir.toFile(), "data.txt").apply { writeText("not an image") }
        assertNull(EditorContextProvider.imageFromFile(txt.absolutePath))
    }
}
