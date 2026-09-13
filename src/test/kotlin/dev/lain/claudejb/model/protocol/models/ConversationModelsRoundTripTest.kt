package dev.lain.claudejb.model.protocol.models

import dev.lain.claudejb.model.protocol.ClaudeJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationModelsRoundTripTest {

    private val block = buildJsonObject {
        put("type", "text")
        put("text", "hi")
    }

    @Test
    fun `the system init and its server statuses survive a round trip, full and empty`() {
        RoundTrip.check(
            SystemInit.serializer(),
            SystemInit("s1", "opus", "/p", listOf("Read"), listOf("/help"), "plan", listOf(McpServerStatus("code", "connected")), "terse", "2.1"),
            SystemInit(),
        )
        RoundTrip.check(McpServerStatus.serializer(), McpServerStatus("vcs", "failed"), McpServerStatus())
    }

    @Test
    fun `the result message and the assistant envelope survive a round trip`() {
        RoundTrip.check(
            ResultMessage.serializer(),
            ResultMessage("success", true, "done", listOf("e"), "s1", 0.5, 3, 1200),
            ResultMessage(),
        )
        RoundTrip.check(
            AssistantInner.serializer(),
            AssistantInner("m1", "opus", "assistant", listOf(block), "end_turn"),
            AssistantInner(),
        )
    }

    @Test
    fun `the initialize response and every model it nests survive a round trip`() {
        val command = SlashCommand("review", "reviews", "<pr>", listOf("r"))
        val model = ModelInfo("claude-opus-5", "Opus 5", "best", true, listOf("low", "high"), true, true, true)
        val account = AccountInfo("a@b", "org", "max", "anthropic", "env")
        RoundTrip.check(
            InitializeResponse.serializer(),
            InitializeResponse(listOf(command), listOf(model), listOf(AgentInfo("x", "y")), "json", listOf("json"), account),
            InitializeResponse(),
        )
        RoundTrip.check(SlashCommand.serializer(), command, SlashCommand("a"))
        RoundTrip.check(ModelInfo.serializer(), model, ModelInfo("m"))
        RoundTrip.check(AccountInfo.serializer(), account, AccountInfo())
        RoundTrip.check(AgentInfo.serializer(), AgentInfo("x", "y"), AgentInfo())
    }

    @Test
    fun `the permission models survive a round trip`() {
        val input = buildJsonObject { put("command", "ls") }
        RoundTrip.check(
            CanUseToolRequest.serializer(),
            CanUseToolRequest("Bash", input, "t", "Bash", "d", "u1", "/x", "why"),
            CanUseToolRequest(),
        )
        val option = AskOption("Yes", "go", "preview")
        RoundTrip.check(AskQuestion.serializer(), AskQuestion("q?", "h", listOf(option), true), AskQuestion())
        RoundTrip.check(AskOption.serializer(), option, AskOption())
        RoundTrip.check(
            ElicitationRequest.serializer(),
            ElicitationRequest("srv", "m", "form", "https://x", "e1", buildJsonObject { put("type", "object") }, "t", "n", "d"),
            ElicitationRequest(),
        )
    }

    @Test
    fun `ask questions are parsed from the input, and a malformed list is no questions`() {
        val questions = buildJsonObject {
            put(
                "questions",
                ClaudeJson.encodeToJsonElement(AskQuestion.serializer(), AskQuestion("q", "h", listOf(AskOption("a")))).let {
                    kotlinx.serialization.json.JsonArray(listOf(it))
                },
            )
        }
        assertEquals(listOf(AskQuestion("q", "h", listOf(AskOption("a")))), parseAskQuestions(questions))
        assertTrue(parseAskQuestions(buildJsonObject { put("questions", "nope") }).isEmpty())
        assertTrue(parseAskQuestions(JsonObject(emptyMap())).isEmpty())
        val broken = buildJsonObject { put("questions", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(3)))) }
        assertTrue(parseAskQuestions(broken).isEmpty())
    }

    @Test
    fun `the session signals survive a round trip`() {
        RoundTrip.check(ThinkingTokensInfo.serializer(), ThinkingTokensInfo(10, 2), ThinkingTokensInfo())
        RoundTrip.check(SessionStateInfo.serializer(), SessionStateInfo("idle"), SessionStateInfo())
        RoundTrip.check(AuthStatusInfo.serializer(), AuthStatusInfo(true, listOf("l"), "e"), AuthStatusInfo())
        RoundTrip.check(ApiRetryInfo.serializer(), ApiRetryInfo(1, 3, 500, 529, "overloaded"), ApiRetryInfo())
        RoundTrip.check(CommandsChangedInfo.serializer(), CommandsChangedInfo(listOf(SlashCommand("a"))), CommandsChangedInfo())
        RoundTrip.check(PromptSuggestionInfo.serializer(), PromptSuggestionInfo("next"), PromptSuggestionInfo())
        RoundTrip.check(
            ControlRequestProgressInfo.serializer(),
            ControlRequestProgressInfo("r1", "started", 1, 2, 300, 500),
            ControlRequestProgressInfo(),
        )
    }

    @Test
    fun `the hook models survive a round trip`() {
        RoundTrip.check(HookStartedInfo.serializer(), HookStartedInfo("h", "lint", "PreToolUse"), HookStartedInfo())
        RoundTrip.check(HookProgressInfo.serializer(), HookProgressInfo("h", "lint", "e", "out", "err", "o"), HookProgressInfo())
        RoundTrip.check(HookResponseInfo.serializer(), HookResponseInfo("h", "lint", "e", "o", "so", "se", 1, "blocked"), HookResponseInfo())
    }

    @Test
    fun `a sparse encoding omits defaults and the strict one writes them`() {
        val sparse = Json(from = ClaudeJson) { encodeDefaults = false }
        assertEquals("{}", sparse.encodeToString(SessionStateInfo.serializer(), SessionStateInfo()))
        assertEquals("""{"state":""}""", ClaudeJson.encodeToString(SessionStateInfo.serializer(), SessionStateInfo()))
    }
}
