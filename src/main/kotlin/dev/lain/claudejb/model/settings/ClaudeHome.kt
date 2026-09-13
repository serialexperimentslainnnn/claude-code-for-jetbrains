package dev.lain.claudejb.model.settings

import com.intellij.openapi.application.ApplicationManager

object ClaudeHome {

    @Volatile
    var override: String? = default()

    private fun default(): String? =
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { "$it/.claude" }

    fun dir(): String? {
        override?.let { if (it != default()) return it }
        val app = ApplicationManager.getApplication()
        return if (app == null || app.isUnitTestMode) null else override
    }
}
