package dev.lain.claudejb.model.session.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class TranscriptCapContractTest {

    private val pageConstant = Regex("""\bconst MAX_ENTRIES = (\d+);""")

    @Test
    fun `the page's row cap is the same number the transcript model enforces`() {
        val source = trimModule().readText()
        val match = pageConstant.find(source)
            ?: fail(
                "$TRIM_MODULE no longer declares `const MAX_ENTRIES = <n>;`. The page states that number " +
                    "in the trimmed-rows notice, so it must stay declared and stay parseable — restore the " +
                    "declaration, or update this contract along with it.",
            )

        assertEquals(TranscriptModel.MAX_ENTRIES, match.groupValues[1].toInt()) {
            "TranscriptModel.MAX_ENTRIES and the page's MAX_ENTRIES have diverged. The model drops " +
                "rows at its own value while the page's trimmed-rows notice claims the other one. Change both."
        }
    }

    private fun trimModule(): File =
        sequenceOf(File(TRIM_MODULE), File("../$TRIM_MODULE")).firstOrNull { it.isFile }
            ?: error("could not locate $TRIM_MODULE from ${File("").absolutePath}")

    private companion object {
        const val TRIM_MODULE = "src/main/ts/jcef/controllers/chat/trim.ts"
    }
}
