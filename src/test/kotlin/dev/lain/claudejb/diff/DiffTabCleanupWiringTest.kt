package dev.lain.claudejb.diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class DiffTabCleanupWiringTest {

    private companion object {
        const val PLUGIN_ID = "dev.lain.claude-code-for-jetbrains"
        const val TOPIC = "com.intellij.openapi.project.ProjectCloseListener"
    }

    private fun parser() = DocumentBuilderFactory.newInstance().apply {
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    private fun ourDescriptors(): List<Element> {
        val urls = javaClass.classLoader.getResources("META-INF/plugin.xml").toList()
        val ours = urls.mapNotNull { url ->
            val root = runCatching { url.openStream().use { parser().parse(it).documentElement } }.getOrNull()
            root?.takeIf { it.getElementsByTagName("id").item(0)?.textContent?.trim() == PLUGIN_ID }
        }
        check(ours.isNotEmpty()) {
            "No plugin.xml with id '$PLUGIN_ID' on the test classpath (scanned ${urls.size} descriptors)"
        }
        return ours
    }

    private fun listeners(tag: String): List<Element> = ourDescriptors().flatMap { descriptor ->
        val groups = descriptor.getElementsByTagName(tag)
        (0 until groups.length).flatMap { i ->
            val children = (groups.item(i) as Element).getElementsByTagName("listener")
            (0 until children.length).map { children.item(it) as Element }
        }
    }

    @Test
    fun `DiffTabCleanup is registered as an application listener on the ProjectCloseListener topic`() {
        val entries = listeners("applicationListeners")
            .filter { it.getAttribute("class") == DiffTabCleanup::class.java.name }
        assertTrue(
            entries.isNotEmpty(),
            "DiffTabCleanup is not declared in <applicationListeners>; diff tabs will be persisted again " +
                "and reappear as 'No file exists: mock:///…' warnings on the next IDE start",
        )
        entries.forEach { entry ->
            assertEquals(
                TOPIC,
                entry.getAttribute("topic"),
                "DiffTabCleanup must subscribe to ProjectCloseListener — it is the only topic that fires " +
                    "projectClosingBeforeSave, i.e. before the workspace state is written",
            )
        }
    }

    @Test
    fun `the cleanup is not registered as a project listener`() {
        assertTrue(
            listeners("projectListeners").none { it.getAttribute("class") == DiffTabCleanup::class.java.name },
            "ProjectCloseListener is an application-bus topic; a projectListeners registration is silently dead",
        )
    }

    @Test
    fun `the declared class exists and implements the listener interface`() {
        val declared = listeners("applicationListeners")
            .filter { it.getAttribute("topic") == TOPIC }
            .map { it.getAttribute("class") }
            .distinct()
        assertTrue(declared.isNotEmpty(), "nothing is registered on the $TOPIC topic")
        declared.forEach { name ->
            assertTrue(
                Class.forName(TOPIC).isAssignableFrom(Class.forName(name)),
                "$name is registered on the ProjectCloseListener topic but does not implement it",
            )
        }
    }
}
