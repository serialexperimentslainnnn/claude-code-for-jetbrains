package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.ui.AttachmentTray

/**
 * Pinning a batch is ONE operation on the page, not one per file.
 *
 * The defect this pins is invisible at the size anyone tests by hand: attaching two files one at a time costs
 * two repaints and two caret moves and nobody notices, while the case the project browser exists to serve —
 * a folder of two hundred — is a stalled panel and a caret that will not sit still. The cost was never in the
 * message (there is one) but in the PUSH, so the assertions are on how many times the host spoke to the page
 * and how many times it took the focus, which is the only place that distinction is visible.
 *
 * The seams are the constructor's: `exec` and `focusInput` are handed in, so counting them needs no browser.
 */
class AttachmentTrayBatchHeadlessTest : BasePlatformTestCase() {

    private val pushes = mutableListOf<String>()
    private var focuses = 0

    private fun tray() = AttachmentTray(project, { pushes += it }, { focuses++ })

    private fun paths(tray: AttachmentTray) = tray.all().filterIsInstance<Attachment.FileRef>().map { it.path }

    fun `test a batch is one push and one focus, however many files it carries`() {
        val tray = tray()

        tray.addPaths(listOf("/p/a.txt", "/p/b.txt", "/p/c.txt"))

        assertEquals(3, tray.all().size)
        assertEquals(1, pushes.size)
        assertEquals(1, focuses)
        // …and the one push describes the WHOLE tray, not the first file of it: a single `exec` carrying a
        // prefix would be the same bug wearing a better number.
        val json = pushes.single()
        assertTrue(json, json.contains("a.txt"))
        assertTrue(json, json.contains("c.txt"))
    }

    fun `test the singular is the plural of one, so there are not two implementations to drift`() {
        val tray = tray()

        tray.addPath("/p/a.txt")

        assertEquals(listOf("/p/a.txt"), paths(tray))
        assertEquals(1, pushes.size)
        assertEquals(1, focuses)
    }

    fun `test the same path cannot occupy two chips, within a batch or across them`() {
        val tray = tray()

        tray.addPaths(listOf("/p/a.txt", "/p/b.txt", "/p/a.txt"))
        assertEquals(listOf("/p/a.txt", "/p/b.txt"), paths(tray))

        tray.addPath("/p/a.txt") // already a chip from the batch above
        assertEquals(listOf("/p/a.txt", "/p/b.txt"), paths(tray))
    }

    fun `test a batch that pins nothing does not repaint and does not take the caret`() {
        val tray = tray()
        tray.addPath("/p/a.txt")
        pushes.clear()
        focuses = 0

        tray.addPaths(listOf("/p/a.txt", "", "   "))

        assertEquals(listOf("/p/a.txt"), paths(tray))
        assertEquals(0, pushes.size)
        assertEquals(0, focuses)
    }

    fun `test the de-duplication is about file paths, not about attachments`() {
        // A selection and an image are legitimately repeatable — two selections from the same file are two
        // different pieces of text — so the rule stops at [Attachment.FileRef] and `add` keeps its behaviour.
        val tray = tray()

        tray.add(Attachment.Selection("/p/a.kt", 3, "one", "kotlin"))
        tray.add(Attachment.Selection("/p/a.kt", 3, "one", "kotlin"))

        assertEquals(2, tray.all().size)
    }
}
