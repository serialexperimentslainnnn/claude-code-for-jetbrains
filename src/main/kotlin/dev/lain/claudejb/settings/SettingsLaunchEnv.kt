package dev.lain.claudejb.settings

import dev.lain.claudejb.process.EnvScriptLoader

// How the persisted settings become the child process's environment.
//
// Extension functions on ClaudeSettings rather than methods on it: that class is the persistence document
// plus the operations that must go through `update`, and env assembly is a pure derivation of the document.
// Split out so this subject — where a credential could leak into a subprocess — is one page, end to end.

/** UI-test harness hook (set only by `runIdeForUiTests`; unset in shipped IDEs). */
private const val FAKE_FIXTURE_PROP = "claudejb.fakeFixture"

/** Parses the `KEY=VALUE` lines (one per line) into an env map; blank/`#`-comment lines ignored. */
fun ClaudeSettings.parseEnv(): Map<String, String> =
    state.envVars.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim() }
        .filterKeys { it.isNotEmpty() }

/**
 * Effective process env: the sourced script's environment first, then explicit overrides on top.
 *
 * SECURITY (trust-on-open): the settings are global now, but they can still ORIGINATE in a repository — a
 * committed `.idea/claude-code.xml` is adopted by [LegacyProjectSettings] when nothing has been stored yet
 * (everything in it except a weakened permission mode, which [LegacyPermissionMode] refuses), so a malicious
 * project can still be the source of the [ClaudeSettings.State.sourceScript] that runs at session start. This
 * method does NOT gate execution itself (it may be called off-EDT); callers must first consult
 * [requiresTrustPrompt] (i.e. [hasRiskyExecConfig] + [isExecutionTrusted]) and obtain user consent before
 * running. The current start flow is intentionally left unchanged here.
 */
fun ClaudeSettings.resolveEnv(): Map<String, String> =
    EnvScriptLoader.load(state.sourceScript) + parseEnv() + fakeFixtureEnv() + providerEnv() + checkpointEnv()

/** Env that routes the binary to the selected provider — empty for Anthropic (native auth). */
private fun ClaudeSettings.providerEnv(): Map<String, String> =
    Provider.launchEnv(provider, getProviderApiKey(provider))

/** Enables the binary's SDK file-checkpointing so native rewind works (env var the SDK uses). */
private fun ClaudeSettings.checkpointEnv(): Map<String, String> =
    if (state.enableFileCheckpointing) mapOf("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING" to "true") else emptyMap()

/**
 * UI-test-only env seeding. When the IDE-under-test is launched with `-Dclaudejb.fakeFixture=<abs path>`
 * (see `runIdeForUiTests`), forward it to the subprocess as `FAKE_FIXTURE` so `bin/fake-claude` replays
 * that JSONL scenario. Explicit `FAKE_FIXTURE` in [ClaudeSettings.State.envVars] still wins (parseEnv is
 * applied after EnvScriptLoader but before this, and a later map entry overrides — so we only set it when
 * absent). Empty in production (property unset).
 */
private fun ClaudeSettings.fakeFixtureEnv(): Map<String, String> {
    if (parseEnv().containsKey("FAKE_FIXTURE")) return emptyMap()
    val fixture = System.getProperty(FAKE_FIXTURE_PROP).orEmpty()
    return if (fixture.isNotBlank()) mapOf("FAKE_FIXTURE" to fixture) else emptyMap()
}
