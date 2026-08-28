package dev.lain.claudejb.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

@JvmInline
value class SettingsScope(val id: String) {

    val secretName: String get() = "${SecretStore.SETTINGS_JSON}@$id"

    val guardLogName: String get() = "${SecretStore.GUARD_LOG}@$id"

    val openChatsName: String get() = "${SecretStore.OPEN_CHATS}@$id"

    val agentIndexName: String get() = "${SecretStore.AGENT_INDEX}@$id"

    companion object {

        fun of(project: Project?): SettingsScope = ofPath(project?.basePath)

        fun ofPath(basePath: String?): SettingsScope = of(installationKey(), basePath)

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
