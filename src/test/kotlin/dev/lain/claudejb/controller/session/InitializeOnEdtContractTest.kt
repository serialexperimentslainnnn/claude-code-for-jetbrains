package dev.lain.claudejb.controller.session

import dev.lain.claudejb.controller.session.control.SessionQueries
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class InitializeOnEdtContractTest {

    @Test
    fun `the initialize reply is adopted through SessionQueries, which hops to the EDT`() {
        val catalog = source("BinaryCatalog.kt").readText()

        assertTrue("queries.ask(" in catalog) {
            "BinaryCatalog no longer asks initialize through SessionQueries.ask. That is the one path with the EDT " +
                "hop built in; a hand-rolled controlClient.query lands the reply on the process reader thread, " +
                "where it writes the five catalogue fields and calls changeModel, which repaints the tab strip."
        }
        assertFalse("controlClient." in catalog) {
            "BinaryCatalog reaches the control client directly, bypassing the EDT hop SessionQueries provides."
        }
    }

    private fun source(name: String): File {
        val path = "src/main/kotlin/dev/lain/claudejb/controller/session/$name"
        return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?: error("could not locate $path from ${File("").absolutePath}")
    }
}
