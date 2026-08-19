package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.LegacyModels

object JcefModelLabels {

    fun modelDisplayLabel(m: ModelInfo): String {
        val descHead = m.description.substringBefore(" · ").trim()
        return when {
            descHead.isNotBlank() -> descHead
            m.displayName.isNotBlank() -> m.displayName
            else -> deriveModelLabel(m.value)
        }
    }

    fun modelLabel(session: ClaudeSession): String {
        val id = session.model ?: session.preferredDefaultModel()
        session.models.firstOrNull { it.value == id }?.let { return modelDisplayLabel(it) }
        LegacyModels.labelFor(id)?.let { return it }
        return deriveModelLabel(id)
    }

    fun deriveModelLabel(id: String): String {
        val core = id.removePrefix("claude-").substringBefore('[').trim()
        if (core.isBlank()) return "Claude"
        val parts = core.split('-')
        val versionStart = parts.indexOfFirst { it.toIntOrNull() != null }
        if (versionStart <= 0) {
            return parts.joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
        }
        val family = parts.subList(0, versionStart)
            .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
        val version = parts.subList(versionStart, parts.size)
            .takeWhile { it.toIntOrNull() != null }
            .joinToString(".")
        return if (version.isNotEmpty()) "$family $version" else family
    }
}
