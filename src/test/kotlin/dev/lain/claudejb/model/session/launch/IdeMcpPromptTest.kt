package dev.lain.claudejb.model.session.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeMcpPromptTest {

    private val own = IdeServer.entries.toSet()
    private val all = IdeRule.entries.toSet()

    @Test
    fun `no server and no rule means no block, and rules without a server earn none`() {
        assertEquals("", IdeMcpPrompt.text(emptySet()))
        assertEquals("", IdeMcpPrompt.text(emptySet(), all))
        assertEquals("", IdeMcpPrompt.rulesBlock(all, emptySet()))
    }

    @Test
    fun `only the servers that are on are listed, each with what it is for`() {
        val text = IdeMcpPrompt.text(setOf(IdeServer.CODE, IdeServer.VCS))
        assertTrue(text.contains("code ("))
        assertTrue(text.contains("vcs ("))
        assertFalse(text.contains("run ("))
        assertFalse(text.contains("ops ("))
    }

    @Test
    fun `the paragraph names the three meta-tools and no tool of any domain, and reads as an order`() {
        val text = IdeMcpPrompt.text(own)
        listOf("domains()", "tools(domain)", "run(tool, args)").forEach { assertTrue(text.contains(it), it) }
        assertFalse(SNAKE_CASE.containsMatchIn(text), "a domain tool is named: " + SNAKE_CASE.find(text)?.value)
        assertTrue(text.startsWith(IdeMcpPrompt.OPEN) && text.endsWith(IdeMcpPrompt.CLOSE))
        assertEquals(3, text.lines().size, "open, one paragraph, close")
        assertNoHedging(text)
    }

    @Test
    fun `rules of a server that is off are left out, and the rules come in one numbered sequence`() {
        val onlyCode = IdeMcpPrompt.text(setOf(IdeServer.CODE), all)
        assertTrue(onlyCode.contains("read_file"))
        assertFalse(onlyCode.contains("git_branches"))
        assertFalse(onlyCode.contains("run_tests"))
        assertTrue(onlyCode.contains("Always:"))
        val text = IdeMcpPrompt.rulesBlock(all, own)
        val numbered = numbered(text)
        assertEquals(IdeRule.entries.size, numbered.size)
        assertEquals((1..IdeRule.entries.size).toList(), numbered.map { it.substringBefore('.').toInt() })
        assertTrue(text.startsWith(IdeMcpPrompt.OPEN) && text.endsWith(IdeMcpPrompt.CLOSE))
    }

    @Test
    fun `every tool of every rule is named in that rule's line, owned by one rule, as a full sentence, without hedging`() {
        val lines = numbered(IdeMcpPrompt.rulesBlock(all, own))
        IdeRule.entries.forEachIndexed { index, rule ->
            val line = lines[index]
            rule.tools.forEach { tool ->
                assertTrue(toolWord(tool).containsMatchIn(line), "${rule.key} does not name $tool")
                val owners = IdeRule.entries.filter { tool in it.tools }.map { it.key }
                assertEquals(listOf(rule.key), owners, "$tool belongs to more than one rule")
            }
            val sentence = line.substringAfter(". ")
            assertTrue(sentence.first().isUpperCase() && sentence.endsWith("."), "${rule.key} is not a sentence: $line")
        }
        assertNoHedging(IdeMcpPrompt.rulesBlock(all, own))
    }

    @Test
    fun `every rule with tools says how the call reaches the IDE or what the user sees`() {
        val lines = numbered(IdeMcpPrompt.rulesBlock(all, own))
        IdeRule.entries.filter { it.tools.isNotEmpty() }.forEachIndexed { index, rule ->
            val line = lines[index].lowercase()
            assertTrue(MECHANISM.any { it in line }, "${rule.key} names neither the IDE's mechanism nor the reveal: $line")
        }
    }

    @Test
    fun `the block's size is printed, not capped`() {
        val text = IdeMcpPrompt.text(own, all)
        println("ide-integration block: ${text.length} chars, ~${text.length / CHARS_PER_TOKEN} tokens")
        assertTrue(text.length > IdeRule.entries.size * MIN_CHARS_PER_RULE, "length=" + text.length)
    }

    @Test
    fun `the rules replace the native tools by name, batch by default, and bind agents to the same way of working`() {
        val text = IdeMcpPrompt.rulesBlock(all, own)
        val expected = "never Bash|never grep|write_file(files)|one message|verbatim|in batches|never from memory|" +
            ".idea/runConfigurations|not even outside the project|never done natively|name it and stop|" +
            "never through a new process|correct that text once|never focused|never switched while the user is in the Terminal|" +
            "no command for which Bash is the right instrument|gh and glab"
        expected.split('|').forEach { assertTrue(text.contains(it), it) }
        listOf("ide_read_file", "jetbrains", "hechtcarmel", "apply_patch").forEach { assertFalse(text.contains(it), it) }
    }

    @Test
    fun `with everything on there is one block holding both parts, and the hook's block is contained in it`() {
        val text = IdeMcpPrompt.text(own, all)
        assertEquals(1, text.lines().count { it == IdeMcpPrompt.OPEN })
        assertEquals(1, text.lines().count { it == IdeMcpPrompt.CLOSE })
        assertTrue(text.contains("domains()") && text.contains("read_file"))
        val hook = IdeMcpPrompt.rulesBlock(all, own)
        hook.lines().forEach { assertTrue(it in text.lines(), it) }
    }

    private fun numbered(text: String): List<String> = text.lines().filter { it.substringBefore('.').toIntOrNull() != null }

    private fun assertNoHedging(text: String) {
        HEDGES.forEach { assertFalse(text.lowercase().contains(it), it) }
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4
        const val MIN_CHARS_PER_RULE = 60

        val SNAKE_CASE = Regex("[a-z]+_[a-z_]+")

        val HEDGES = listOf("prefer", "try to", "if possible", "consider", "when possible")

        val MECHANISM = listOf(
            "through the ide",
            "the ide's",
            "over the socket",
            "the user sees",
            "without taking the focus",
            "shows",
            "window",
            "in the log",
        )

        fun toolWord(tool: String) = Regex("(?<![a-z_])" + Regex.escape(tool) + "(?![a-z_])")
    }
}
