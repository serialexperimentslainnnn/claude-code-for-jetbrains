package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of [ClipboardCli]'s one I/O-free decision: which advertised target to read as text.
 * Everything else in that object spawns `wl-paste`/`xclip` and cannot run on a CI box.
 */
class ClipboardCliTest {

    // --- preferredTextType: the Wayland/X11 clipboard-text guard (image-vs-text selection) ---

    @Test
    fun `preferredTextType prefers a utf-8 plain-text mime`() {
        assertEquals(
            "text/plain;charset=utf-8",
            ClipboardCli.preferredTextType(listOf("text/html", "text/plain;charset=utf-8", "text/plain")),
        )
    }

    @Test
    fun `preferredTextType accepts X11 atom targets from xclip`() {
        // xclip TARGETS for a typical text copy — no text/plain;charset variant, atoms instead.
        assertEquals(
            "UTF8_STRING",
            ClipboardCli.preferredTextType(listOf("TARGETS", "STRING", "UTF8_STRING", "TEXT")),
        )
        assertEquals("STRING", ClipboardCli.preferredTextType(listOf("TARGETS", "STRING", "TEXT")))
    }

    @Test
    fun `preferredTextType falls back to plain then any other text type`() {
        assertEquals("text/plain", ClipboardCli.preferredTextType(listOf("text/html", "text/plain")))
        assertEquals("text/markdown", ClipboardCli.preferredTextType(listOf("text/markdown")))
    }

    @Test
    fun `preferredTextType returns null for an image-only clipboard (the Wayland leak guard)`() {
        // KDE Plasma screenshot copy: image types + a suggested-filename, but NO real text/* target.
        val kdeImageTypes = listOf(
            "image/png",
            "application/x-qt-image",
            "x-kde-force-image-copy",
            "application/x-kde-suggestedfilename",
            "image/avif",
            "image/bmp",
        )
        assertNull(ClipboardCli.preferredTextType(kdeImageTypes))
    }

    @Test
    fun `preferredTextType excludes uri-list and html (copied files and markup are not a plain paste)`() {
        // A file copied from a file manager advertises text/uri-list — must NOT be read as pasted text.
        assertNull(ClipboardCli.preferredTextType(listOf("text/uri-list")))
        // html-only (no plain) is not the plain-paste target either.
        assertNull(ClipboardCli.preferredTextType(listOf("text/html")))
    }

    @Test
    fun `preferredTextType returns null for an empty listing`() {
        assertNull(ClipboardCli.preferredTextType(emptyList()))
    }
}
