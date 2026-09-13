package dev.lain.claudejb.model.settings

internal object IdeRulesAdoption {

    fun adopt(s: ClaudeSettings.State) {
        if (s.ideMcp.catalogue == LaunchDefaults.DEFAULT_IDE_RULES) return
        if (s.ideMcp.catalogue.isEmpty()) s.ideMcp.enabled = true
        s.ideMcp.rules = adopted(s.ideMcp.rules, s.ideMcp.catalogue)
        s.ideMcp.catalogue = LaunchDefaults.DEFAULT_IDE_RULES
    }

    fun adopted(csv: String, catalogue: String): String {
        val known = keys(LaunchDefaults.DEFAULT_IDE_RULES)
        val stored = keys(csv)
        val previous = keys(catalogue)
        if (previous.isEmpty() || stored.any { it !in known }) return LaunchDefaults.DEFAULT_IDE_RULES
        return known.filter { it in stored || it !in previous }.joinToString(",")
    }

    private fun keys(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
