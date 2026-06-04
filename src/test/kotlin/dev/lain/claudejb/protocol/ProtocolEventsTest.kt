package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * E1 regression suite: pins the shape of every system subtype and top-level event the binary emits that the
 * plugin now parses natively (previously dropped as [ClaudeEvent.Other]). Field names mirror sdk.d.ts; a
 * failure here means the binary changed a contract we reconstruct UI state from. The parser must never
 * throw, so a few cases also assert malformed payloads degrade to [ClaudeEvent.Other] instead.
 */
class ProtocolEventsTest {

    private inline fun <reified T : ClaudeEvent> parseOne(line: String): T {
        val events = ProtocolParser.parse(line)
        assertEquals(1, events.size, "expected exactly one event from: $line")
        return assertInstanceOf(T::class.java, events.first())
    }

    // --- subagent task lifecycle ---

    @Test
    fun `task_started decodes id description and subagent type`() {
        val line = """{"type":"system","subtype":"task_started","task_id":"t1","tool_use_id":"tu1",
            "description":"Investigate","subagent_type":"explore","skip_transcript":false}""".trimIndent()
        val e = parseOne<ClaudeEvent.TaskStarted>(line)
        assertEquals("t1", e.info.taskId)
        assertEquals("tu1", e.info.toolUseId)
        assertEquals("Investigate", e.info.description)
        assertEquals("explore", e.info.subagentType)
        assertEquals(false, e.info.skipTranscript)
    }

    @Test
    fun `task_progress decodes usage last tool and summary`() {
        val line = """{"type":"system","subtype":"task_progress","task_id":"t1","description":"working",
            "subagent_type":"explore","usage":{"total_tokens":1234,"tool_uses":5,"duration_ms":900},
            "last_tool_name":"Grep","summary":"halfway"}""".trimIndent()
        val e = parseOne<ClaudeEvent.TaskProgress>(line)
        assertEquals("t1", e.info.taskId)
        assertEquals(1234L, e.info.usage.totalTokens)
        assertEquals(5, e.info.usage.toolUses)
        assertEquals(900L, e.info.usage.durationMs)
        assertEquals("Grep", e.info.lastToolName)
        assertEquals("halfway", e.info.summary)
    }

    @Test
    fun `task_updated decodes the patch`() {
        val line = """{"type":"system","subtype":"task_updated","task_id":"t1",
            "patch":{"status":"running","description":"d2","is_backgrounded":true,"end_time":42}}""".trimIndent()
        val e = parseOne<ClaudeEvent.TaskUpdated>(line)
        assertEquals("t1", e.info.taskId)
        assertEquals("running", e.info.patch.status)
        assertEquals("d2", e.info.patch.description)
        assertEquals(true, e.info.patch.isBackgrounded)
        assertEquals(42L, e.info.patch.endTime)
    }

    @Test
    fun `task_notification decodes status summary and usage`() {
        val line = """{"type":"system","subtype":"task_notification","task_id":"t1","status":"completed",
            "output_file":"/tmp/out.md","summary":"done","usage":{"total_tokens":10,"tool_uses":2,"duration_ms":5}}""".trimIndent()
        val e = parseOne<ClaudeEvent.TaskNotification>(line)
        assertEquals("completed", e.info.status)
        assertEquals("/tmp/out.md", e.info.outputFile)
        assertEquals("done", e.info.summary)
        assertEquals(10L, e.info.usage?.totalTokens)
    }

    // --- tool progress / summary ---

    @Test
    fun `tool_progress decodes elapsed time and parent id`() {
        val line = """{"type":"tool_progress","tool_use_id":"tu1","tool_name":"Bash",
            "parent_tool_use_id":null,"elapsed_time_seconds":12.5,"task_id":"t1"}""".trimIndent()
        val e = parseOne<ClaudeEvent.ToolProgress>(line)
        assertEquals("tu1", e.info.toolUseId)
        assertEquals("Bash", e.info.toolName)
        assertNull(e.info.parentToolUseId)
        assertEquals(12.5, e.info.elapsedTimeSeconds)
        assertEquals("t1", e.info.taskId)
    }

    @Test
    fun `tool_use_summary decodes summary and preceding ids`() {
        val line = """{"type":"tool_use_summary","summary":"read 3 files","preceding_tool_use_ids":["a","b","c"]}"""
        val e = parseOne<ClaudeEvent.ToolUseSummary>(line)
        assertEquals("read 3 files", e.info.summary)
        assertEquals(listOf("a", "b", "c"), e.info.precedingToolUseIds)
    }

    // --- thinking / notification / auth / retry / state ---

    @Test
    fun `thinking_tokens decodes estimate and delta`() {
        val line = """{"type":"system","subtype":"thinking_tokens","estimated_tokens":512,"estimated_tokens_delta":16}"""
        val e = parseOne<ClaudeEvent.ThinkingTokens>(line)
        assertEquals(512, e.info.estimatedTokens)
        assertEquals(16, e.info.estimatedTokensDelta)
    }

    @Test
    fun `notification decodes text priority color and timeout`() {
        val line = """{"type":"system","subtype":"notification","key":"k","text":"Heads up","priority":"high",
            "color":"yellow","timeout_ms":3000}""".trimIndent()
        val e = parseOne<ClaudeEvent.Notification>(line)
        assertEquals("Heads up", e.info.text)
        assertEquals("high", e.info.priority)
        assertEquals("yellow", e.info.color)
        assertEquals(3000L, e.info.timeoutMs)
    }

    @Test
    fun `permission_denied decodes tool reason and decision`() {
        val line = """{"type":"system","subtype":"permission_denied","tool_name":"Bash","tool_use_id":"tu1",
            "decision_reason_type":"rule","decision_reason":"blocked by deny rule","message":"Not allowed"}""".trimIndent()
        val e = parseOne<ClaudeEvent.PermissionDenied>(line)
        assertEquals("Bash", e.info.toolName)
        assertEquals("tu1", e.info.toolUseId)
        assertEquals("rule", e.info.decisionReasonType)
        assertEquals("blocked by deny rule", e.info.decisionReason)
        assertEquals("Not allowed", e.info.message)
    }

    @Test
    fun `session_state_changed decodes state`() {
        val e = parseOne<ClaudeEvent.SessionStateChanged>(
            """{"type":"system","subtype":"session_state_changed","state":"idle"}"""
        )
        assertEquals("idle", e.info.state)
    }

    @Test
    fun `auth_status is a top-level type with output and error`() {
        val line = """{"type":"auth_status","isAuthenticating":true,"output":["line1","line2"],"error":"boom"}"""
        val e = parseOne<ClaudeEvent.AuthStatus>(line)
        assertTrue(e.info.isAuthenticating)
        assertEquals(listOf("line1", "line2"), e.info.output)
        assertEquals("boom", e.info.error)
    }

    @Test
    fun `api_retry decodes attempt limits and error status`() {
        val line = """{"type":"system","subtype":"api_retry","attempt":2,"max_retries":5,
            "retry_delay_ms":2000,"error_status":529}""".trimIndent()
        val e = parseOne<ClaudeEvent.ApiRetry>(line)
        assertEquals(2, e.info.attempt)
        assertEquals(5, e.info.maxRetries)
        assertEquals(2000L, e.info.retryDelayMs)
        assertEquals(529, e.info.errorStatus)
    }

    @Test
    fun `api_retry tolerates null error_status`() {
        val e = parseOne<ClaudeEvent.ApiRetry>(
            """{"type":"system","subtype":"api_retry","attempt":1,"max_retries":3,"retry_delay_ms":500,"error_status":null}"""
        )
        assertNull(e.info.errorStatus)
    }

    // --- commands / memory / files / suggestion / plugin ---

    @Test
    fun `commands_changed decodes the replacement command list`() {
        val line = """{"type":"system","subtype":"commands_changed","commands":[
            {"name":"foo","description":"do foo"},{"name":"bar"}]}""".trimIndent()
        val e = parseOne<ClaudeEvent.CommandsChanged>(line)
        assertEquals(2, e.info.commands.size)
        assertEquals("foo", e.info.commands[0].name)
        assertEquals("do foo", e.info.commands[0].description)
        assertEquals("bar", e.info.commands[1].name)
    }

    @Test
    fun `memory_recall decodes mode and memories`() {
        val line = """{"type":"system","subtype":"memory_recall","mode":"select","memories":[
            {"path":"/m/a.md","scope":"personal"},{"path":"<synthesis:/x>","scope":"team","content":"note"}]}""".trimIndent()
        val e = parseOne<ClaudeEvent.MemoryRecall>(line)
        assertEquals("select", e.info.mode)
        assertEquals(2, e.info.memories.size)
        assertEquals("/m/a.md", e.info.memories[0].path)
        assertEquals("personal", e.info.memories[0].scope)
        assertEquals("note", e.info.memories[1].content)
    }

    @Test
    fun `files_persisted decodes persisted and failed lists`() {
        val line = """{"type":"system","subtype":"files_persisted",
            "files":[{"filename":"a.png","file_id":"f1"}],
            "failed":[{"filename":"b.png","error":"too big"}],"processed_at":"2026-06-03T00:00:00Z"}""".trimIndent()
        val e = parseOne<ClaudeEvent.FilesPersisted>(line)
        assertEquals(1, e.info.files.size)
        assertEquals("f1", e.info.files[0].fileId)
        assertEquals(1, e.info.failed.size)
        assertEquals("too big", e.info.failed[0].error)
        assertEquals("2026-06-03T00:00:00Z", e.info.processedAt)
    }

    @Test
    fun `prompt_suggestion is a top-level type`() {
        val e = parseOne<ClaudeEvent.PromptSuggestion>(
            """{"type":"prompt_suggestion","suggestion":"Try running the tests"}"""
        )
        assertEquals("Try running the tests", e.info.suggestion)
    }

    @Test
    fun `plugin_install decodes status name and error`() {
        val e = parseOne<ClaudeEvent.PluginInstall>(
            """{"type":"system","subtype":"plugin_install","status":"failed","name":"acme","error":"404"}"""
        )
        assertEquals("failed", e.info.status)
        assertEquals("acme", e.info.name)
        assertEquals("404", e.info.error)
    }

    // --- hooks ---

    @Test
    fun `hook_started decodes ids and event`() {
        val e = parseOne<ClaudeEvent.HookStarted>(
            """{"type":"system","subtype":"hook_started","hook_id":"h1","hook_name":"fmt","hook_event":"PreToolUse"}"""
        )
        assertEquals("h1", e.info.hookId)
        assertEquals("fmt", e.info.hookName)
        assertEquals("PreToolUse", e.info.hookEvent)
    }

    @Test
    fun `hook_progress decodes stdout and stderr`() {
        val line = """{"type":"system","subtype":"hook_progress","hook_id":"h1","hook_name":"fmt",
            "hook_event":"PreToolUse","stdout":"working","stderr":"warn","output":"o"}""".trimIndent()
        val e = parseOne<ClaudeEvent.HookProgress>(line)
        assertEquals("working", e.info.stdout)
        assertEquals("warn", e.info.stderr)
        assertEquals("o", e.info.output)
    }

    @Test
    fun `hook_response decodes outcome and exit code`() {
        val line = """{"type":"system","subtype":"hook_response","hook_id":"h1","hook_name":"fmt",
            "hook_event":"PreToolUse","output":"done","stdout":"o","stderr":"","exit_code":0,"outcome":"success"}""".trimIndent()
        val e = parseOne<ClaudeEvent.HookResponse>(line)
        assertEquals("success", e.info.outcome)
        assertEquals(0, e.info.exitCode)
        assertEquals("done", e.info.output)
    }

    // --- mirror_error ---

    @Test
    fun `mirror_error decodes error and key`() {
        val line = """{"type":"system","subtype":"mirror_error","error":"append failed",
            "key":{"projectKey":"pk","sessionId":"s1","subpath":"sub"}}""".trimIndent()
        val e = parseOne<ClaudeEvent.MirrorError>(line)
        assertEquals("append failed", e.info.error)
        assertEquals("pk", e.info.key.projectKey)
        assertEquals("s1", e.info.key.sessionId)
        assertEquals("sub", e.info.key.subpath)
    }

    // --- robustness: never throw on a hostile shape ---

    @Test
    fun `system event with hostile field shape degrades to Other`() {
        // `usage` is typed as an object; arriving as a scalar must not crash the reader.
        val line = """{"type":"system","subtype":"task_progress","task_id":"t1","description":"x","usage":"nope"}"""
        val e = parseOne<ClaudeEvent.Other>(line)
        assertEquals("system", e.type)
        assertEquals("task_progress", e.subtype)
    }

    @Test
    fun `unknown system subtype still degrades to Other`() {
        val e = parseOne<ClaudeEvent.Other>("""{"type":"system","subtype":"brand_new_subtype_2099"}""")
        assertEquals("system", e.type)
        assertEquals("brand_new_subtype_2099", e.subtype)
    }
}
