package dev.lain.claudejb.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ApiKeyApprovalTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var home: File

    private val config get() = ApiKeyApproval.configFile()

    private val existing = """
        {
          "numStartups": 42,
          "installMethod": "native",
          "mcpServers": {"jetbrains": {"type": "sse", "url": "http://127.0.0.1:64342/sse"}},
          "projects": {"/home/u/work": {"allowedTools": ["Bash(git:*)"], "history": [{"display": "hi"}]}},
          "oauthAccount": {"emailAddress": "dev@example.com", "accountUuid": "acc-1"}
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        home = createTempDirectory("claudejb-apikey").toFile()
        ApiKeyApproval.homeOverride = home
        AccountProfile.homeOverride = home
    }

    @AfterEach
    fun tearDown() {
        ApiKeyApproval.homeOverride = null
        AccountProfile.homeOverride = null
        home.deleteRecursively()
    }

    private fun write(text: String) = config.writeText(text)

    private fun read(): JsonObject = json.parseToJsonElement(config.readText()).jsonObject

    private fun approvedList(root: JsonObject): List<String> =
        root["customApiKeyResponses"]!!.jsonObject["approved"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `approve records the suffix and leaves every other field byte-for-byte equivalent`() {
        write(existing)
        val before = json.parseToJsonElement(existing).jsonObject

        assertTrue(ApiKeyApproval.approve("sk-ant-api03-" + "a".repeat(60)))

        val after = read()
        before.forEach { (k, v) -> assertEquals(v, after[k], "field `$k` was not preserved") }
        assertEquals(before.keys + "customApiKeyResponses", after.keys)
    }

    @Test
    fun `the recorded identifier is the last 20 characters, not the key`() {
        val key = "sk-ant-api03-" + "a".repeat(60)
        write(existing)
        ApiKeyApproval.approve(key)

        val recorded = approvedList(read())
        assertEquals(listOf(key.takeLast(20)), recorded)
        assertEquals(20, recorded.single().length)
        assertFalse(config.readText().contains(key), "the KEY itself must never reach the config file")
        assertEquals(key.takeLast(20), ApiKeyApproval.suffixOf(key))
    }

    @Test
    fun `an existing approval is kept and the new one appended`() {
        write("""{"customApiKeyResponses":{"approved":["other-suffix-0000000"]}}""")
        ApiKeyApproval.approve("sk-ant-api03-" + "b".repeat(60))

        assertEquals(listOf("other-suffix-0000000", "b".repeat(20)), approvedList(read()))
    }

    @Test
    fun `approving twice is idempotent and does not duplicate the entry`() {
        val key = "sk-ant-api03-" + "c".repeat(60)
        write(existing)

        assertTrue(ApiKeyApproval.approve(key))
        val once = config.readText()
        assertTrue(ApiKeyApproval.approve(key), "an already-approved key is still approved")
        assertEquals(once, config.readText(), "a no-op approval must not rewrite the user's config")
        assertEquals(1, approvedList(read()).size)
    }

    @Test
    fun `a previously rejected key is un-rejected, so it can be re-supplied without editing JSON by hand`() {
        val key = "sk-ant-api03-" + "d".repeat(60)
        val suffix = key.takeLast(20)
        write("""{"customApiKeyResponses":{"approved":[],"rejected":["$suffix","someone-elses-000000"]}}""")

        assertTrue(ApiKeyApproval.approve(key))

        val responses = read()["customApiKeyResponses"]!!.jsonObject
        assertEquals(listOf(suffix), responses["approved"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("someone-elses-000000"),
            responses["rejected"]!!.jsonArray.map { it.jsonPrimitive.content },
            "another key's rejection is not ours to clear",
        )
    }

    @Test
    fun `sibling keys inside customApiKeyResponses survive the rewrite`() {
        write("""{"customApiKeyResponses":{"approved":[],"somethingElse":{"a":1}}}""")
        ApiKeyApproval.approve("sk-ant-api03-" + "e".repeat(60))

        val responses = read()["customApiKeyResponses"]!!.jsonObject
        assertEquals(JsonPrimitive(1), responses["somethingElse"]!!.jsonObject["a"])
    }

    @Test
    fun `a missing config is not created`() {
        assertFalse(config.exists())
        assertFalse(ApiKeyApproval.approve("sk-ant-api03-" + "f".repeat(60)))
        assertFalse(config.exists(), "the plugin must not invent the CLI's config file")
    }

    @Test
    fun `an unparseable config is left exactly as it was`() {
        val garbage = "{ this is not json"
        write(garbage)

        assertFalse(ApiKeyApproval.approve("sk-ant-api03-" + "g".repeat(60)))
        assertEquals(garbage, config.readText())
    }

    @Test
    fun `a blank key is refused`() {
        write(existing)
        assertFalse(ApiKeyApproval.approve("   "))
        assertEquals(json.parseToJsonElement(existing), json.parseToJsonElement(config.readText()))
    }

    @Test
    fun `harvesting the Console key strips it and preserves the rest of the config`() {
        val key = "sk-ant-api03-" + "h".repeat(60)
        write(
            """
            {"numStartups":42,"primaryApiKey":"$key",
             "mcpServers":{"jetbrains":{"type":"sse"}},
             "customApiKeyResponses":{"approved":["${key.takeLast(20)}"]}}
            """.trimIndent(),
        )

        assertEquals(key, ConsoleApiKey.harvest())

        val after = read()
        assertNull(after["primaryApiKey"], "the key must not stay in the plaintext config")
        assertFalse(config.readText().contains(key))
        assertEquals(JsonPrimitive(42), after["numStartups"])
        assertEquals("sse", after["mcpServers"]!!.jsonObject["jetbrains"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf(key.takeLast(20)), approvedList(after))
    }

    @Test
    fun `harvest is a no-op when there is no Console key`() {
        write(existing)
        assertNull(ConsoleApiKey.harvest())
        assertEquals(json.parseToJsonElement(existing), json.parseToJsonElement(config.readText()))
    }

    @Test
    fun `ApiKeyApproval and AccountProfile resolve the SAME file`() {
        assertEquals(ApiKeyApproval.configFile().absolutePath, AccountProfile.configFile().absolutePath)
        assertEquals(File(home, ".claude.json").absolutePath, ApiKeyApproval.configFile().absolutePath)
    }
}
