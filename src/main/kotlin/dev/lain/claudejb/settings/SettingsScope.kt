package dev.lain.claudejb.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Which settings document a window reads and writes: **one per IDE installation, per project**.
 *
 * The installation half is [PathManager.getConfigPath], which is where the running IDE keeps its own
 * configuration — it differs across products and across major versions, which is exactly what "this IDE"
 * means for a setting. The project half is the opened directory. Neither half is stored: the id is derived
 * on every call, so two IDEs pointed at one checkout keep their own settings and neither can surprise the
 * other.
 *
 * The id is a truncated digest rather than the paths themselves because it becomes the tail of a
 * PasswordSafe entry name, which some backends surface to the user — a home directory does not belong in a
 * keyring label. Truncation is safe here: a collision costs two projects one shared document, not a
 * disclosure, and SCOPE_ID_BYTES is far past the point where that is worth thinking about.
 */
@JvmInline
value class SettingsScope(val id: String) {

    /** The PasswordSafe entry this scope's document lives under. */
    val secretName: String get() = "${SecretStore.SETTINGS_JSON}@$id"

    /** And the one its guard alert log lives under — same scope, separate entry, separate lifetime. */
    val guardLogName: String get() = "${SecretStore.GUARD_LOG}@$id"

    /** The chats this project had open when it was last closed. */
    val openChatsName: String get() = "${SecretStore.OPEN_CHATS}@$id"

    /** The agent/task tree of this project's sessions. */
    val agentIndexName: String get() = "${SecretStore.AGENT_INDEX}@$id"

    companion object {

        /**
         * The scope [project] reads and writes.
         *
         * A window with no directory on disk — the default/template project, and the detached instance a
         * unit test builds — shares one fixed scope rather than inventing one, because it has nothing
         * stable to derive an identity from.
         */
        fun of(project: Project?): SettingsScope = ofPath(project?.basePath)

        /**
         * The scope **this** installation uses for [basePath], for a project that is not the open one.
         *
         * Migration and the import-from-another-IDE dialog both need to address a project by path rather
         * than by an open window, and the identity is the same calculation either way.
         */
        fun ofPath(basePath: String?): SettingsScope = of(installationKey(), basePath)

        /** The pure half, so the identity can be reasoned about — and tested — without an IDE around it. */
        internal fun of(installation: String, basePath: String?): SettingsScope {
            val base = basePath?.takeIf { it.isNotBlank() } ?: return SettingsScope(NO_PROJECT)
            return SettingsScope(digest("$installation $base"))
        }

        private fun installationKey(): String =
            runCatching { PathManager.getConfigPath() }.getOrNull()?.takeIf { it.isNotBlank() } ?: NO_PROJECT

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .take(SCOPE_ID_BYTES)
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }

        private const val NO_PROJECT = "default"

        private const val SCOPE_ID_BYTES = 8
    }
}
