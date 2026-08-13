package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.LegacyModels

/**
 * How a model is NAMED on screen — the one place it is decided.
 *
 * Shared on purpose: the composer pill and menu ([JcefComposerOptions]), the dashboard's Session card
 * ([JcefSessionData]) and the Settings combo renderer all call these, so the same model cannot read as two
 * different things depending on which control you are looking at.
 */
object JcefModelLabels {

    /**
     * The pill/menu label for a model, WITH its version — everything derived from the binary's own catalog, so it
     * stays correct as tiers change (no hardcoded version anywhere). The binary's `displayName` omits the version
     * ("Opus (1M context)"); the version lives in `description` ("Opus 5 with 1M context · Best for everyday…").
     * We prefer the description's lead segment (before the " · " tagline) because it carries the version; fall back
     * to `displayName`, then to a label derived from the id.
     */
    fun modelDisplayLabel(m: ModelInfo): String {
        val descHead = m.description.substringBefore(" · ").trim()
        return when {
            descHead.isNotBlank() -> descHead
            m.displayName.isNotBlank() -> m.displayName
            else -> deriveModelLabel(m.value)
        }
    }

    /** The model pill label: the catalog's versioned label for the selected model, else derived from its id. */
    fun modelLabel(session: ClaudeSession): String {
        val id = session.model ?: session.preferredDefaultModel()
        session.models.firstOrNull { it.value == id }?.let { return modelDisplayLabel(it) }
        // A model picked from "Other models" is not in the catalog, so the pill would fall through to
        // deriveModelLabel — which is right for `claude-opus-4-7` and wrong for `claude-3-5-sonnet`, where the
        // version leads the family and it renders "3 5 Sonnet". The curated label is the authority for ours.
        LegacyModels.labelFor(id)?.let { return it }
        return deriveModelLabel(id)
    }

    /** Turns a model id like "claude-opus-4-8" into a friendly label like "Opus 4.8"; a last resort when the binary
     *  catalog carries no metadata for it. Strips an alias suffix like "opus[1m]" → "Opus" (no version to show). */
    fun deriveModelLabel(id: String): String {
        val core = id.removePrefix("claude-").substringBefore('[').trim()
        if (core.isBlank()) return "Claude"
        // Split family from the version digits: "opus-4-8" → family "opus", version ["4","8"].
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
