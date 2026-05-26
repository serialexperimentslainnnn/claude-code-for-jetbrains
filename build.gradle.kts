plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.lain"
version = "0.1.0"

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

intellijPlatform {
    pluginConfiguration {
        // id/name/vendor/description live in META-INF/plugin.xml; only compatibility range is set here.
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "261.*"
        }
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
