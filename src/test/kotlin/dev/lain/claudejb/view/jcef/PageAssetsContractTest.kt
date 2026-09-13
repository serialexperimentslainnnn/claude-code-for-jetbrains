package dev.lain.claudejb.view.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageAssetsContractTest {

    private fun present(resource: String): Boolean = PageAssembly::class.java.getResource(resource) != null

    @Test
    fun `the page declares a full set of scripts and stylesheets`() {
        assertTrue(PageAssembly.appNames.size >= MIN_SCRIPTS) { "only ${PageAssembly.appNames.size} scripts declared" }
        assertTrue(PageAssembly.CSS_PARTS.size >= MIN_STYLES) { "only ${PageAssembly.CSS_PARTS.size} stylesheets declared" }
    }

    @Test
    fun `every declared script and stylesheet is on the classpath the page is built from`() {
        val missing = PageAssembly.appNames.filterNot { present("/jcef/$it") } +
            PageAssembly.CSS_PARTS.filterNot { present("/jcef/css/$it") } +
            listOf("shell.html").filterNot { present("/jcef/$it") }
        assertEquals(emptyList<String>(), missing) {
            "A declared asset is not in the resources the jar is built from. A TypeScript module that did not " +
                "emit, or a name that was renamed on one side only, would ship a page missing part of its UI."
        }
    }

    private companion object {
        const val MIN_SCRIPTS = 30
        const val MIN_STYLES = 8
    }
}
