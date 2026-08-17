package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * The transcript's memory cap is one number held in two languages, and they cannot be allowed to disagree.
 *
 * [TranscriptModel.MAX_ENTRIES] is what actually drops rows; the JCEF page mirrors it as `MAX_ENTRIES` in
 * `app-transcript.js` so the trimmed-rows notice can state the ceiling the model enforces. Nothing at build
 * time connects the two — one is Kotlin, the other is a string in a resource file — so a change to either
 * side alone is invisible: the page would keep telling the user a number that stopped being true, and no
 * test, linter or compiler anywhere would notice. That silence is the whole reason this gate exists.
 *
 * The JS value is PARSED out of the served source rather than written down here. A test that repeated the
 * number would be a third copy to keep in sync, and the first one to go stale.
 */
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

    /** Resolves the served JS whether the test runs from the module dir or the repo root. */
    private fun transcriptJs(): File =
        sequenceOf(
            File("src/main/resources/jcef/app-transcript.js"),
            File("../src/main/resources/jcef/app-transcript.js"),
        ).firstOrNull { it.isFile }
            ?: error("could not locate src/main/resources/jcef/app-transcript.js from ${File("").absolutePath}")
}
