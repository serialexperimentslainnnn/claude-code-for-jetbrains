package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object ResourceHijacking {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private val AT = CommandRules.AT_COMMAND

    private const val MINERS =
        "xmrig|minerd|cpuminer|cgminer|bfgminer|ethminer|nbminer|lolminer|phoenixminer|" +
            "xmr-stak|t-rex|nheqminer|ccminer|teamredminer|gminer|srbminer|nanominer|" +
            "wildrig|sgminer|ethdcrminer64"

    private val VECTORS: List<Regex> = listOf(
        re(AT + "($MINERS)(?=[-\\s]|\$|[;&|])"),
        re("""\bstratum2?\+(tcp|ssl|tcps)://"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        VECTORS.firstNotNullOfOrNull { it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
}
