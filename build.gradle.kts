import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.lain"
version = "2.0.1"

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

    // Unit tests (pure JVM: protocol parsing/building, no IntelliJ Platform fixtures needed).
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IntelliJ Platform Gradle plugin wires a JUnit4-based runtime into the test task; its executor
    // references JUnit4 API (org.junit.rules.TestRule) even when our tests are JUnit5, so it must be present.
    testRuntimeOnly("junit:junit:4.13.2")
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
    test {
        useJUnitPlatform()
    }
}

intellijPlatform {
    pluginConfiguration {
        // id/name/vendor/description live in META-INF/plugin.xml; only compatibility range is set here.
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "262.*"
        }
        // "What's new" on the Marketplace = the latest version section of RELEASE_NOTES.md, as HTML.
        changeNotes = provider { latestReleaseNotesHtml() }
    }

    // Marketplace publishing + plugin signing. All credentials come from the environment (GitHub Actions
    // secrets); never commit them. Locally these are simply absent and the publish/sign tasks aren't run.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        // 'JetBrains' in the plugin name is a Marketplace naming lint, not an API problem; muting it lets
        // the verifier proceed to the actual binary-compatibility / internal-API checks we care about.
        freeArgs = listOf("-mute", "TemplateWordInPluginName")
        ides {
            // On CI there's no local IDE install: verify against the recommended IDEs (downloaded on demand).
            // Locally, verify against the developer's real runtime to avoid an extra download.
            if (providers.environmentVariable("CI").isPresent) {
                recommended()
            } else {
                local(file("/home/dexperiments/.local/share/JetBrains/Toolbox/apps/intellij-idea"))
            }
            // Validate against the current EAP (2026.2 / build 262) before promising it via untilBuild.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                channels = listOf(ProductRelease.Channel.EAP, ProductRelease.Channel.RC)
                sinceBuild = "262"
                untilBuild = "262.*"
            }
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
