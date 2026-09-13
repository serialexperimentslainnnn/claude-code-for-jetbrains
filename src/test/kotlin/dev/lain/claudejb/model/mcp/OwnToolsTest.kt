package dev.lain.claudejb.model.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnToolsTest {

    private fun input(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun args(json: String) = OwnTools.argsOf(input(json))

    @Test
    fun `a call to one of our servers is recognised and labelled by server, tool and subject`() {
        val run = OwnTools.parse("mcp__code__run", input("""{"tool":"find_symbols","args":{"query":"Mcp"}}"""))!!
        assertEquals("code ▸ find_symbols", OwnTools.label(run))
        assertEquals("query: Mcp", OwnTools.argsToon(args("""{"tool":"find_symbols","args":{"query":"Mcp"}}""")))
        assertEquals("ops ▸ tools(services)", OwnTools.label(OwnTools.parse("mcp__ops__tools", input("""{"domain":"services"}"""))!!))
        assertEquals("vcs ▸ domains", OwnTools.label(OwnTools.parse("mcp__vcs__domains", input("{}"))!!))
        val search = input("""{"tool":"search_text","args":{"query":"Mcp","regex":true}}""")
        assertEquals("code ▸ search_text ▸ Mcp", OwnTools.label(OwnTools.parse("mcp__code__run", search)!!, OwnTools.argsOf(search)))
        val named = input("""{"tool":"run_configuration","args":{"name":"Kotlin tests"}}""")
        assertEquals("run ▸ run_configuration ▸ Kotlin tests", OwnTools.label(OwnTools.parse("mcp__run__run", named)!!, OwnTools.argsOf(named)))
        assertTrue(OwnTools.isOwn("mcp__run__run"))
        assertFalse(OwnTools.isOwn("mcp__jetbrains__get_file_text"))
        assertFalse(OwnTools.isOwn("Bash"))
        assertNull(OwnTools.argsToon(args("""{"tool":"x","args":{}}""")))
        assertNull(OwnTools.argsToon(args("""{"tool":"x"}""")))
    }

    @Test
    fun `the guard sees a run call flat, with the tool name kept and every argument at the top level`() {
        val flat = OwnTools.guardInput(input("""{"tool":"write_file","args":{"path":"/home/u/.bash_aliases","content":"alias"}}"""))
        assertEquals("write_file", flat["tool"]!!.jsonPrimitive.content)
        assertEquals("/home/u/.bash_aliases", flat["path"]!!.jsonPrimitive.content)
        assertEquals("alias", flat["content"]!!.jsonPrimitive.content)
        assertNull(flat["args"])
        val bash = input("""{"command":"ls"}""")
        assertEquals(bash, OwnTools.guardInput(bash))
        val noArgs = input("""{"tool":"x"}""")
        assertEquals(noArgs, OwnTools.guardInput(noArgs))
    }

    @Test
    fun `every surface that names a call gets the same display name, and a foreign tool gets none`() {
        val commit = input("""{"tool":"git_commit","args":{"message":"m","paths":["A.kt"]}}""")
        assertEquals("vcs ▸ git_commit", OwnTools.display("mcp__vcs__run", commit))
        assertEquals("code ▸ read_file ▸ src/A.kt", OwnTools.display("mcp__code__run", input("""{"tool":"read_file","args":{"path":"src/A.kt"}}""")))
        assertNull(OwnTools.display("Bash", input("""{"command":"ls"}""")))
        assertNull(OwnTools.display("mcp__jetbrains__get_file_text", input("{}")))
    }

    @Test
    fun `a result decodes from TOON to JSON for the card, comment primer included, and garbage decodes to nothing`() {
        val decoded = OwnTools.decodeResult("# TOON primer\ndomains[2]{name,description}:\n  read,Files\n  search,Text\n")!!.jsonObject
        assertEquals(2, decoded["domains"]!!.jsonArray.size)
        assertNull(OwnTools.decodeResult("name: \"unterminated"))
    }

    @Test
    fun `a call on a file carries its path in the title, an edit becomes a review of the native kind, a read its text`() {
        val edit = args("""{"tool":"replace_text","args":{"path":"src/A.kt","old_string":"a","new_string":"b"}}""")
        val call = OwnTools.parse("mcp__code__run", input("""{"tool":"replace_text"}"""))!!
        assertEquals("code ▸ replace_text ▸ src/A.kt", OwnTools.label(call, edit))
        assertEquals("src/A.kt", OwnTools.path(edit))
        assertTrue(OwnTools.isEdit(call) && !OwnTools.isRead(call))
        val review = OwnTools.reviewAs(call, edit, "/home/u/proj")!!
        assertEquals("Edit", review.toolName)
        assertEquals("/home/u/proj/src/A.kt", review.input["file_path"]!!.jsonPrimitive.content)
        assertEquals("a", review.input["old_string"]!!.jsonPrimitive.content)
        assertNull(review.input["path"])

        val writing = OwnTools.parse("mcp__code__run", input("""{"tool":"write_file"}"""))!!
        val write = args("""{"tool":"write_file","args":{"path":"/abs/B.kt","content":"x"}}""")
        assertEquals("Write", OwnTools.reviewAs(writing, write, "/home/u/proj")!!.toolName)
        assertEquals("/abs/B.kt", OwnTools.reviewAs(writing, write, null)!!.input["file_path"]!!.jsonPrimitive.content)

        val inserting = OwnTools.parse("mcp__code__run", input("""{"tool":"insert_text"}"""))!!
        val insert = args("""{"tool":"insert_text","args":{"path":"C.kt","line":3,"content":"y"}}""")
        val inserted = OwnTools.reviewAs(inserting, insert, "/p")!!
        assertEquals("InsertText", inserted.toolName)
        val asWrite = OwnTools.asWrite(inserted, "a\nb\nc\n")!!
        assertEquals("Write", asWrite.toolName)
        assertEquals("/p/C.kt", asWrite.input["file_path"]!!.jsonPrimitive.content)
        assertEquals("a\nb\ny\nc\n", asWrite.input["content"]!!.jsonPrimitive.content)
        assertNull(asWrite.input["line"])

        val reading = OwnTools.parse("mcp__code__run", input("""{"tool":"read_file"}"""))!!
        assertTrue(OwnTools.isRead(reading))
        assertNull(OwnTools.reviewAs(reading, args("""{"args":{"path":"src/A.kt"}}"""), "/p"))
        assertEquals("fun a()", OwnTools.readText(OwnTools.decodeResult("path: src/A.kt\nlines: 1\ntext: \"fun a()\"\n")))
        assertNull(OwnTools.readText(OwnTools.decodeResult("count: 0\n")))
    }
}
