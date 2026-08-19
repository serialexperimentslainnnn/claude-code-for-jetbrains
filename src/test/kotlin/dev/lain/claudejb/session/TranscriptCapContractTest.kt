package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class TranscriptCapContractTest {

    private val jsConstant = Regex("""\bvar MAX_ENTRIES = (\d+);""")

    @Test
    fun `the page's row cap is the same number the transcript model enforces`() {
        val source = transcriptJs().readText()
        val match = jsConstant.find(source)
            ?: fail(
                "app-transcript.js no longer declares `var MAX_ENTRIES = <n>;`. The page states that number " +
                    "in the trimmed-rows notice, so it must stay declared and stay parseable — restore the " +
                    "declaration, or update this contract along with it.",
            )

        assertEquals(TranscriptModel.MAX_ENTRIES, match.groupValues[1].toInt()) {
            "TranscriptModel.MAX_ENTRIES and app-transcript.js's MAX_ENTRIES have diverged. The model drops " +
                "rows at its own value while the page's trimmed-rows notice claims the other one. Change both."
        }
    }

    private fun transcriptJs(): File =
        sequenceOf(
            File("src/main/resources/jcef/app-transcript.js"),
            File("../src/main/resources/jcef/app-transcript.js"),
        ).firstOrNull { it.isFile }
            ?: error("could not locate src/main/resources/jcef/app-transcript.js from ${File("").absolutePath}")
}
