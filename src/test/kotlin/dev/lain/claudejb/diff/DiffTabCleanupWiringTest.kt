package dev.lain.claudejb.diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the `plugin.xml` wiring of [DiffTabCleanup].
 *
 * The failure mode this exists for is **silence**. [DiffTabCleanup] has no callers in our code: the platform
 * instantiates it from a `plugin.xml` declaration. Rename the class, move it to another package, or reach for
 * `projectListeners` instead of `applicationListeners`, and nothing breaks loudly — the listener simply stops
 * being registered, diff tabs start being persisted again, and the only symptom is a `WARN` in a log nobody
 * reads. Same class of bug as a CI ruleset that references a renamed job: the gate stops applying without
 * ever failing.
 *
 * So this asserts the three things that must agree, from the XML the plugin actually ships.
 */
class DiffTabCleanupWiringTest {

    private companion object {
        const val PLUGIN_ID = "dev.lain.claude-code-for-jetbrains"
        const val TOPIC = "com.intellij.openapi.project.ProjectCloseListener"
    }

    private fun parser() = DocumentBuilderFactory.newInstance().apply {
        // Our own resource, but a parser that resolves external entities is never the right default.
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    /**
     * EVERY plugin.xml on the test classpath that claims our id — resolved by id, not by classpath order.
     *
     * Two things make this less obvious than it looks. First, the test classpath is the plugin's own, so it
     * carries the whole IntelliJ Platform, and every bundled plugin ships a `META-INF/plugin.xml`;
     * `getResourceAsStream` would hand back whichever one the classloader reached first, and the assertions
     * below would then be checking someone else's descriptor and passing for reasons unrelated to this plugin.
     *
     * Second, more than one descriptor legitimately claims OUR id: the IntelliJ Platform Gradle plugin emits a
     * patched copy (version and since/until-build substituted) alongside the source one, and it is the patched
     * copy that actually ships. So the assertions run against ALL of them and every one must agree — which
     * verifies the shipped descriptor rather than assuming it matches the source.
     */
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

    /** Every `<listener>` declared under [tag], across every descriptor that claims our id. */
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
        // ProjectCloseListener is published on the APPLICATION message bus (ProjectManagerImpl obtains it via
        // Application.getMessageBus). Declared under <projectListeners> it would never be invoked.
        assertTrue(
            listeners("projectListeners").none { it.getAttribute("class") == DiffTabCleanup::class.java.name },
            "ProjectCloseListener is an application-bus topic; a projectListeners registration is silently dead",
        )
    }

    @Test
    fun `the declared class exists and implements the listener interface`() {
        // Guards against the registration outliving a rename/move of the class itself.
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
