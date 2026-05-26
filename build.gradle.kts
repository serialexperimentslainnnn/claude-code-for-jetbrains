plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.lain"
version = "1.3.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target IntelliJ IDEA Community 2025.1. The plugin still loads in newer IDEs
        // (e.g. 2026.1) because untilBuild is widened below.
        create("IC", "2025.1")
    }

    // JSON (de)serialization for the stream-json / control protocol.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// The Kotlin stdlib and JetBrains annotations are provided by the IntelliJ Platform at runtime; keep
// them out of the bundled plugin (where they would shadow the platform copies and trip the verifier).
// runtimeClasspath is the configuration the plugin is assembled from.
configurations.named("runtimeClasspath") {
    exclude(mapOf("group" to "org.jetbrains.kotlin", "module" to "kotlin-stdlib"))
    exclude(mapOf("group" to "org.jetbrains", "module" to "annotations"))
}

tasks {
    runIde {
        jvmArgs("-Djb.privacy.policy.text=<!--999.999-->", "-Djb.consents.confirmation.enabled=false")
    }
}

intellijPlatform {
    pluginConfiguration {
        // id/name/vendor/description live in META-INF/plugin.xml; only compatibility range is set here.
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "261.*"
        }
        // "What's new" on the Marketplace = the latest version section of RELEASE_NOTES.md, as HTML.
        changeNotes = provider { latestReleaseNotesHtml() }
    }

    pluginVerification {
        // 'JetBrains' in the plugin name is a Marketplace naming lint, not an API problem; muting it lets
        // the verifier proceed to the actual binary-compatibility / internal-API checks we care about.
        freeArgs = listOf("-mute", "TemplateWordInPluginName")
        ides {
            // Verify against the locally installed IntelliJ IDEA (the user's real runtime, no extra download).
            // For CI/Marketplace, replace with `recommended()`.
            local(file("/home/dexperiments/.local/share/JetBrains/Toolbox/apps/intellij-idea"))
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

/** Extracts the top (latest) `## vX.Y.Z` section of RELEASE_NOTES.md and renders it as the HTML subset
 *  the Marketplace accepts for change notes. Falls back to a generic line if the file is missing. */
fun latestReleaseNotesHtml(): String {
    val notes = file("RELEASE_NOTES.md")
    if (!notes.exists()) return "See RELEASE_NOTES.md."
    val lines = notes.readLines()
    val start = lines.indexOfFirst { it.startsWith("## v") }
    if (start < 0) return "See RELEASE_NOTES.md."
    val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## v") }.let {
        if (it < 0) lines.size else start + 1 + it
    }

    fun inline(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("`(.+?)`"), "<code>$1</code>")

    val html = StringBuilder()
    var inList = false
    fun closeList() { if (inList) { html.append("</ul>"); inList = false } }

    for (raw in lines.subList(start, end)) {
        val line = raw.trim()
        when {
            line.startsWith("## v") -> html.append("<p><b>").append(inline(line.removePrefix("## ").trim())).append("</b></p>")
            line == "---" || line.isEmpty() -> closeList()
            line.startsWith("- ") -> {
                if (!inList) { html.append("<ul>"); inList = true }
                html.append("<li>").append(inline(line.removePrefix("- ").trim())).append("</li>")
            }
            else -> { closeList(); html.append("<p>").append(inline(line)).append("</p>") }
        }
    }
    closeList()
    return html.toString()
}
