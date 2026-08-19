package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **A live session's options have exactly one writer, and the compiler cannot say so.**
 *
 * Those properties used to be `private set`: the UI read them and only [ClaudeSession] could write them, which
 * is a guarantee the language enforced. Moving the seven verbs that change them into [SessionLiveSettings]
 * meant widening the setters to `internal`, i.e. to the whole module — so the guarantee stopped being
 * structural and has to be a gate instead. This is that gate.
 *
 * **Why it is worth a test rather than a comment.** One of these fields is `permissionMode`, and
 * `bypassPermissions` lives in it: whoever writes it decides whether the plugin asks about ordinary tool calls
 * at all. It never opens the deterministic guard — `PermissionBroker` refuses to let the mode answer a guard
 * card — but everything outside the guard is downstream of this field, and a second writer appearing by
 * autocomplete is exactly the change nobody would notice in review.
 *
 * **What it can and cannot catch**, stated rather than implied. It matches an assignment through a receiver
 * named `session` (`session.model = …`, `chat.session.permissionMode = …`), which is how every call site in
 * this repository spells it and how a new one would be written. It cannot catch an assignment through an alias
 * the reader gave a different name, and it deliberately does not try: a pattern loose enough for that would
 * also match `it.permissionMode` on a `ClaudeSettings.State`, which is a different field with the same name and
 * a legitimate writer. A gate with false positives gets silenced, and a silenced gate protects nothing.
 */
class SessionStateOwnershipTest {

    /** The properties whose setters were opened to the module, i.e. the ones this test exists for. */
    private val owned = listOf(
        "model", "effort", "permissionMode", "thinkingTokens",
        "allowedTools", "disallowedTools", "settingSources", "includePartialMessages",
        "ideMcpEnabled", "ideMcpTransport", "ideMcpPort", "customMcpServers",
        "maxTurns", "maxBudgetUsd", "fallbackModel", "addDirs", "betas", "strictMcpConfig",
        "cachedEnv",
    )

    /**
     * The two files allowed to write them: the class that declares them, and the one collaborator that owns the
     * verbs. Adding a third name here is the decision this test exists to make somebody argue for.
     */
    private val writers = setOf("ClaudeSession.kt", "SessionLiveSettings.kt")

    private fun productionSources(): List<File> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "the source root moved: this gate has to move with it")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `only the session and its live settings write a session's options`() {
        val assignment = Regex("""\bsession\.(${owned.joinToString("|")})\s*=(?!=)""")
        val offenders = productionSources()
            .filter { it.name !in writers }
            .flatMap { file ->
                assignment.findAll(file.readText()).map { "${file.name}: ${it.value.trim()}" }
            }

        assertEquals(
            emptyList<String>(),
            offenders,
            "a second writer of a live session's options appeared — see this test's doc before adding one",
        )
    }

    @Test
    fun `the guarded list still matches what the session declares as internally settable`() {
        // Without this, the list above rots silently: a property renamed or a new one added with `internal set`
        // would simply stop being guarded, and the first test would keep passing while covering less.
        val source = File("src/main/kotlin/dev/lain/claudejb/session/ClaudeSession.kt").readText()
        val declared = Regex("""var (\w+)[^\n]*\n\s*internal set""").findAll(source)
            .map { it.groupValues[1] }
            .toSet()

        // `sessionId` is settable internally for a different reason (the launcher reports it) and is not a
        // launch option, so it is named here rather than silently missing from `owned`.
        assertEquals(emptySet<String>(), declared - owned.toSet() - setOf("sessionId"))
    }
}
