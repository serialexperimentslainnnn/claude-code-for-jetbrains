package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
