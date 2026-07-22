import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    // Coverage report so we can hit the ≥90% target on src/main/ documented in docs/RELEASE_CHECKLIST.md.
    id("org.jetbrains.kotlinx.kover") version "0.9.2"
}

group = "dev.lain"
version = "4.3.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// ---------------------------------------------------------------------------
// Test layout (the pyramid in docs/ + the plan):
//   src/test, package `…` and `…headless`/`…integration`  → `test` (unit) + `integrationTest` (headless+fake-claude)
//   src/uiTest                                            → `uiTest` (RemoteRobot, drives an external IDE; gated)
// Headless/integration tests live in src/test so they inherit the IntelliJ Platform classpath the plugin
// already wires for the `test` task (a custom source set does NOT get `Project` on its classpath). The
// `integrationTest` task simply re-runs the test classes filtered to the heavy packages; `test` excludes them.
// uiTest is a real separate source set: it talks to a running IDE over HTTP (remote-robot), so it must NOT
// pull the platform into its own classpath.
// ---------------------------------------------------------------------------
sourceSets {
    create("uiTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + compileClasspath
        kotlin.srcDir("src/uiTest/kotlin")
        resources.srcDir("src/uiTest/resources")
    }
}

configurations {
    named("uiTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
    named("uiTestRuntimeOnly")    { extendsFrom(configurations.testRuntimeOnly.get()) }
}

dependencies {
    intellijPlatform {
        // Compile against IntelliJ IDEA Community 2025.2 — the declared since-build floor (252), so we build
        // against the oldest IDE we support (never below it) and the plugin still loads in newer IDEs because
        // untilBuild is widened below.
        create("IC", "2025.2")
        // Bundled IDE Terminal: used to open an interactive `claude login` session (the OAuth flow needs a
        // TTY, which the stream-json process doesn't have). Compile-only coupling; TerminalLauncher guards
        // its use behind PluginManager.isPluginInstalled so a disabled Terminal plugin degrades gracefully.
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // JSON (de)serialization for the stream-json / control protocol.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Unit tests (pure JVM: protocol parsing/building, no IntelliJ Platform fixtures needed).
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // JUnit4/3 on the COMPILE classpath: the plugin's test executor references JUnit4 API, and
    // BasePlatformTestCase (headless component tests) descends from JUnit3 `junit.framework.TestCase`.
    testImplementation("junit:junit:4.13.2")

    // Headless/integration tests (in src/test) use BasePlatformTestCase, which descends from JUnit3 TestCase;
    // the vintage engine lets the JUnit Platform discover and run them alongside the JUnit5 unit tests.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    // Full IntelliJ Platform test fixtures (BasePlatformTestCase, LightVirtualFile, EDT helpers).
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
    }

    // --- uiTest: RemoteRobot end-to-end (Layer D), gated by -PuiTest.enabled=true ---
    "uiTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "uiTestImplementation"("org.junit.jupiter:junit-jupiter")
    "uiTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "uiTestImplementation"("com.intellij.remoterobot:remote-robot:0.11.23")
    "uiTestImplementation"("com.intellij.remoterobot:remote-fixtures:0.11.23")
    "uiTestImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
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
        // Exclude the live drift check: it downloads the latest SDK from npm and spawns the real binary,
        // so it must not run in the default suite. It lives in the `checkDrift` task below. (excludeTags only
        // affects JUnit5/jupiter discovery — the JUnit3 vintage headless tests carry no tags and still run.)
        useJUnitPlatform { excludeTags("driftLive") }
        // Runs the whole non-UI pyramid: unit (jupiter) + headless/integration (BasePlatformTestCase via the
        // vintage engine). The IntelliJ Platform Gradle plugin only instruments ITS `test` task with the
        // platform runtime, so headless tests must run here rather than in a hand-rolled Test task.
        systemProperty("claudejb.fakeClaude", rootProject.file("bin/fake-claude").absolutePath)
    }

    // On-demand protocol drift watcher (NOT wired into `check`). Downloads the latest published SDK and
    // probes the locally-installed (auto-updated) `claude` binary, then prints an agent-consumable report
    // and fails on real surface drift. Runs only the `driftLive`-tagged DriftLiveCheck against the test
    // classpath (the pure extraction/diff logic is covered offline by DriftDetectorTest in the normal suite).
    //   ./gradlew checkDrift                          # uses ~/.local/bin/claude
    //   ./gradlew checkDrift -PclaudeBinary=/path     # or CLAUDE_BINARY env var
    val checkDrift by registering(Test::class) {
        description = "Download latest SDK + probe the installed binary; report protocol drift (on-demand)."
        group = "verification"
        // Only the jupiter engine: the vintage engine would try to DISCOVER (instantiate) the JUnit3
        // headless BasePlatformTestCase classes, which aren't on this task's classpath (only the plugin's
        // own `test` task gets the platform runtime) — that fails before tag filtering even applies.
        useJUnitPlatform { includeTags("driftLive"); includeEngines("junit-jupiter") }
        // Belt-and-suspenders: restrict discovery to the drift package.
        filter { includeTestsMatching("dev.lain.claudejb.drift.*") }
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        // Always re-run (it polls the network + binary); never serve a cached result.
        outputs.upToDateWhen { false }
        val binaryPath = (providers.gradleProperty("claudeBinary").orNull
            ?: providers.environmentVariable("CLAUDE_BINARY").orNull
            ?: "${System.getProperty("user.home")}/.local/bin/claude")
        systemProperty("claudejb.drift.projectDir", rootProject.projectDir.absolutePath)
        systemProperty("claudejb.drift.sdkDir",
            rootProject.file("node_modules/@anthropic-ai/claude-agent-sdk").absolutePath)
        systemProperty("claudejb.drift.binary", binaryPath)
        systemProperty("claudejb.drift.baseline", rootProject.file("scripts/drift-baseline.properties").absolutePath)
        // Surface the report (println from the test) on the console.
        testLogging { showStandardStreams = true }
    }

    // Convenience alias: run only the heavy IntelliJ-fixture packages (headless + fake-claude integration).
    val integrationTest by registering {
        description = "Runs only the headless + fake-claude integration tests (subset of `test`)."
        group = "verification"
        finalizedBy(named("test"))
        doFirst {
            (named("test").get() as Test).filter {
                includeTestsMatching("dev.lain.claudejb.headless.*")
                includeTestsMatching("dev.lain.claudejb.integration.*")
            }
        }
    }

    val uiTest by registering(Test::class) {
        description = "End-to-end UI tests driving the IDE via RemoteRobot (Layer D)."
        group = "verification"
        useJUnitPlatform()
        testClassesDirs = sourceSets["uiTest"].output.classesDirs
        classpath = sourceSets["uiTest"].runtimeClasspath
        // RemoteRobot's HTTP client (Retrofit + Gson) reflects into JDK-internal fields to (de)serialize
        // responses/exceptions; under JDK 17+ strong encapsulation that throws InaccessibleObjectException
        // unless we open the relevant java.base packages to the (unnamed) test module.
        jvmArgs(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
        )
        shouldRunAfter("integrationTest")
        // Let a remote runner override where the robot-server lives (defaults to 127.0.0.1:8082 in UiTestBase).
        System.getProperty("robot-server.url")?.let { systemProperty("robot-server.url", it) }
        // RemoteRobot needs a running IDE + display; opt in explicitly (CI nightly with Xvfb, or local).
        onlyIf { project.findProperty("uiTest.enabled") == "true" }
    }

    // The uiTest source set inherits the test classpath, so the sandbox-project fixture can be contributed
    // from more than one resource root; tolerate the duplicate deterministically instead of failing the copy.
    named<Copy>("processUiTestResources") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // `check` already depends on `test`, which now includes the headless/integration packages.
    // uiTest stays out of `check` — runs nightly / manual (needs a display + running IDE).
}

// ---------------------------------------------------------------------------
// RemoteRobot harness (Layer D). `intellijPlatformTesting.runIde` is the canonical 2.x DSL for a custom
// IDE-under-test: it builds a sandbox, installs extra plugins (here `robotServerPlugin()`, which exposes the
// HTTP endpoint RemoteRobot talks to), and lets us tune the JVM. We launch it on port 8082 and point the
// plugin at `bin/fake-claude` via the two `claudejb.fake*` system properties (read by ClaudeSettings only
// when those props are present — a no-op in shipped IDEs).
//
// Flow (two terminals / two background steps — the IDE must be UP before the client tests connect):
//   1. ./gradlew runIdeForUiTests        # starts the IDE with robot-server on :8082 (keep it running)
//   2. ./gradlew uiTest -PuiTest.enabled=true   # the RemoteRobot client suite connects to :8082
// Headless CI: wrap step 1 in `xvfb-run`. See docs/UI_TESTING.md.
// ---------------------------------------------------------------------------
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            // Install the robot-server plugin into this IDE's sandbox; that's what RemoteRobot drives.
            plugins {
                robotServerPlugin()
            }
            task {
                // Open the minimal sandbox project so the IDE doesn't sit on the "open a project" screen — the
                // tool window, composer and editor context only exist with a Project. We open a tiny build-less
                // project (not this repo) to keep the suite fast and free of Gradle-import/trust prompts. The
                // IDE opens the directory passed as the first positional CLI arg.
                args(rootProject.file("src/uiTest/resources/sandbox-project").absolutePath)
                jvmArgs(
                    // RemoteRobot endpoint (UiTestBase connects to http://127.0.0.1:8082).
                    "-Drobot-server.port=8082",
                    // Quiet, deterministic first run: no privacy/consent gates, no tips, no "what's new".
                    "-Djb.privacy.policy.text=<!--999.999-->",
                    "-Djb.consents.confirmation.enabled=false",
                    "-Dide.show.tips.on.startup.default.value=false",
                    "-Dide.mac.message.dialogs.as.sheets=false",
                    "-Dide.mac.file.chooser.native=false",
                    "-DjbScreenMenuBar.enabled=false",
                    "-Dapple.laf.useScreenMenuBar=false",
                    // Auto-trust opened projects so no "Trust this project?" modal blocks the robot.
                    "-Dide.trust.all.projects=true",
                    "-Dide.show.new.ui.welcome.screen=false",
                    // Point the plugin at the deterministic fake binary + default fixture (per-test scenarios
                    // can override FAKE_FIXTURE; see docs/UI_TESTING.md). Read by ClaudeSettings test hook.
                    "-Dclaudejb.fakeClaude=${rootProject.file("bin/fake-claude").absolutePath}",
                    "-Dclaudejb.fakeFixture=${rootProject.file("src/test/resources/fixtures/multi_message.jsonl").absolutePath}",
                )
            }
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        // id/name/vendor/description live in META-INF/plugin.xml; only compatibility range is set here.
        ideaVersion {
            // Floor 251 (2025.1): as far back as the plugin reaches WITHOUT shipping a deprecated API — the hard
            // limit is `FileChooserDescriptorFactory.multiFiles()/singleDir()` in FilePickerHelper, which does not
            // exist before 251 (verified: NoSuchMethodError on IC-242/IC-243), and whose pre-251 equivalents are
            // deprecated on current IDEs. A runtime `if` would not help — the verifier reads bytecode, so the
            // broken reference ships either way. Users pinned to 2024.x would need a separate 242-targeted build
            // (JetBrains' documented approach for a range where the API actually changed).
            // The ceiling tracks the latest verified EAP/RC branch (see pluginVerification.select below).
            sinceBuild = "251"
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
            // No hardcoded path in the repo: a developer can point the verifier at local IDE installs to skip the
            // downloads, via -PlocalIdePath=<dir>[,<dir>…] or the LOCAL_IDE_PATH env var (comma-separated). This is
            // what makes an OFFLINE verification of the whole declared range possible — download.jetbrains.com is
            // not always reachable from every network, and the verifier is the only thing that catches a *binary*
            // incompatibility (see InstalledPlugins: `PluginId` is a Kotlin class since 2025.2, so `PluginId.getId`
            // compiles fine and then dies with NoSuchFieldError on 242–251).
            // When unset/missing (or on CI), fall back to recommended() — which spans the plugin's whole declared
            // range including the since-build FLOOR, the gate that catches a too-new API.
            val localIdes = (providers.gradleProperty("localIdePath").orNull
                ?: providers.environmentVariable("LOCAL_IDE_PATH").orNull)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { file(it) }
                ?.filter { it.exists() }
                .orEmpty()
            if (localIdes.isNotEmpty() && !providers.environmentVariable("CI").isPresent) {
                localIdes.forEach { local(it) }
            } else {
                recommended()
            }
            // Always validate against the NEWEST EAP/RC available before promising it via the plugin's
            // untilBuild. The range upper bound is kept one branch ahead (263.*) so that when the next EAP
            // ships the verifier picks it up automatically; today it simply resolves to the latest 262 build.
            // The plugin's own untilBuild (pluginConfiguration above) stays at the latest *verified* branch.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                channels = listOf(ProductRelease.Channel.EAP, ProductRelease.Channel.RC)
                sinceBuild = "262"
                untilBuild = "263.*"
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
