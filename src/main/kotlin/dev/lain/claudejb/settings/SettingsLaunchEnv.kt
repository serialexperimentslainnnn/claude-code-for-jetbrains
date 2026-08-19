package dev.lain.claudejb.settings

import dev.lain.claudejb.process.EnvScriptLoader

private const val FAKE_FIXTURE_PROP = "claudejb.fakeFixture"

fun ClaudeSettings.parseEnv(): Map<String, String> =
    state.envVars.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim() }
        .filterKeys { it.isNotEmpty() }

fun ClaudeSettings.resolveEnv(): Map<String, String> =
    auditedScriptEnv() + parseEnv() + fakeFixtureEnv() + providerEnv() + checkpointEnv()

private fun ClaudeSettings.auditedScriptEnv(): Map<String, String> {
    val path = state.sourceScript.trim()
    if (path.isEmpty()) return emptyMap()
    val finding = SourceScriptAudit.findingIn(path, sensitivePolicy(project?.basePath))
    if (finding != null) {
        SourceScriptAudit.refused(path, finding)
        return emptyMap()
    }
    return EnvScriptLoader.load(path)
}

private fun ClaudeSettings.providerEnv(): Map<String, String> =
    Provider.launchEnv(provider, getProviderApiKey(provider))

private fun ClaudeSettings.checkpointEnv(): Map<String, String> =
    if (state.enableFileCheckpointing) mapOf("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING" to "true") else emptyMap()

private fun ClaudeSettings.fakeFixtureEnv(): Map<String, String> {
    if (parseEnv().containsKey("FAKE_FIXTURE")) return emptyMap()
    val fixture = System.getProperty(FAKE_FIXTURE_PROP).orEmpty()
    return if (fixture.isNotBlank()) mapOf("FAKE_FIXTURE" to fixture) else emptyMap()
}
