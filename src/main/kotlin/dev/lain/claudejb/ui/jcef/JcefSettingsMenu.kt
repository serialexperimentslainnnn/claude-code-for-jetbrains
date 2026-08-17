package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.settings.ClaudeSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * The composer's ⚙ menu: the settings worth flipping without leaving the chat.
 *
 * **Deliberately NOT the whole Settings page.** That page has forty-odd fields, and three of them are a JSON
 * document, a table of environment variables and a list of tool names — a popup that reproduced those would
 * be the page again, with two interfaces writing one configuration and the drift that follows. What is here
 * is the set you change WHILE working: whether the guard should keep stopping you, whether motion bothers
 * you, whether a restart brings your chats back. Everything else is one row away, behind *Open Plugin
 * Settings*.
 *
 * **The keys are a closed set and the page never invents one.** The host sends `{key,label,on}` and paints
 * nothing itself; an inbound key that is not in [apply]'s `when` is dropped. A settings write is the last
 * place to accept an arbitrary string from a browser, and a `when` over an enum-shaped constant is what makes
 * "unknown" a case rather than an assignment.
 */
internal object JcefSettingsMenu {

    /** The menu, in the order it is drawn. Groups are a UI concern, so they travel with the entries. */
    fun json(s: ClaudeSettings.State): JsonArray = buildJsonArray {
        entry("restoreChats", "Chat", "Restore open chats on startup", s.restoreOpenChatsOnStartup)
        entry("reduceMotion", "Chat", "Reduce motion", s.reduceMotion)
        entry("checkpointing", "Chat", "Let Claude rewind file changes", s.enableFileCheckpointing)
        entry("partialMessages", "Chat", "Stream partial messages", s.includePartialMessages)

        // The six the guard is made of. They are here because the moment you want one is the moment it has
        // just refused something — and a trip to a settings dialog then is a trip taken while annoyed.
        // Turning one off never grants anything silently: it downgrades a refusal to a card you still answer.
        entry("blockCredentials", "Security", "Block credential files", s.securityBlockCredentials)
        entry("blockDangerous", "Security", "Block dangerous commands", s.securityBlockDangerousCommands)
        entry("blockTempDirs", "Security", "Block the system temp folder", s.securityBlockTempDirs)
        entry("blockOtherHomes", "Security", "Block other users' home folders", s.securityBlockForeignOtherUserHome)
        entry("blockNetworkMounts", "Security", "Block network mounts", s.securityBlockForeignNetworkMounts)
        entry("blockWslMounts", "Security", "Block other WSL drives", s.securityBlockForeignWslMounts)
    }

    /**
     * Applies [key], or answers false when this build does not know it.
     *
     * The caller persists and re-pushes; this only writes the field, so one unknown key cannot leave half a
     * settings document written.
     */
    fun apply(state: ClaudeSettings.State, key: String, on: Boolean): Boolean {
        when (key) {
            "restoreChats" -> state.restoreOpenChatsOnStartup = on
            "reduceMotion" -> state.reduceMotion = on
            "checkpointing" -> state.enableFileCheckpointing = on
            "partialMessages" -> state.includePartialMessages = on
            "blockCredentials" -> state.securityBlockCredentials = on
            "blockDangerous" -> state.securityBlockDangerousCommands = on
            "blockTempDirs" -> state.securityBlockTempDirs = on
            "blockOtherHomes" -> state.securityBlockForeignOtherUserHome = on
            "blockNetworkMounts" -> state.securityBlockForeignNetworkMounts = on
            "blockWslMounts" -> state.securityBlockForeignWslMounts = on
            else -> return false
        }
        return true
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.entry(
        key: String,
        group: String,
        label: String,
        on: Boolean,
    ) = addJsonObject {
        put("key", key)
        put("group", group)
        put("label", label)
        put("on", on)
    }
}
