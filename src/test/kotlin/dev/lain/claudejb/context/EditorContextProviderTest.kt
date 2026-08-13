package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of the project-free helper on [EditorContextProvider]: the extension→lang map that tags
 * a pasted selection's fence. The editor accessors need a Project/EDT and are exercised in the headless
 * suite instead; the clipboard subsystems have their own tests ([ClipboardCliTest], [ImageAttachmentsTest]).
 */
class EditorContextProviderTest {

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
}
