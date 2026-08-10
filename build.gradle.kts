import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    // PINNED AT 2.16.0 DELIBERATELY. 2.18.1 exists and the build warns about it on every run, but bumping it
    // hangs the headless suite: `ChatSessionManagerHeadlessTest` never starts, because
    // BasePlatformTestCase.setUp → LightPlatformTestCase.doSetup → IndexingTestUtil.waitUntilIndexesAreReady
    // waits forever (confirmed by thread dump — the EDT sits in that frame; our code is never reached). The
    // bump changes which platform test-framework is resolved, so this is a fixture-level regression, not ours
    // to fix from here. Re-attempt as its own change, with the headless suite as the acceptance test — NOT as
    // a drive-by inside a release branch, which is exactly how it got in and straight back out.
    id("org.jetbrains.intellij.platform") version "2.16.0"
    // Coverage, gated per package — see the `kover { }` block near the bottom for the thresholds and why they
    // differ by package. (Until 5.0.0 this comment claimed a "≥90% target documented in
    // docs/RELEASE_CHECKLIST.md". That document says nothing about coverage, and the real figure was 53%. A
    // number nobody measured, pointing at a requirement that did not exist.)
    id("org.jetbrains.kotlinx.kover") version "0.9.2"
    // Static analysis (detekt) and formatting (ktlint via Spotless). Added in 5.0.0: until then the whole
    // quality bar rested on review, which is exactly the thing the standards say to mechanise — "if format
    // is being discussed in a review, a formatter is missing".
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.diffplug.spotless") version "8.9.0"
}

group = "dev.lain"
version = "5.5.0"

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
    named("uiTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
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
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
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
    "uiTestImplementation"(platform("org.junit:junit-bom:6.1.2"))
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
    // Attribution travels INSIDE the artifact (opensource-licensing-standards §5.4).
    //
    // The published zip redistributes third-party code: marked, DOMPurify and highlight.js are vendored into
    // the plugin jar under `jcef/`, and kotlinx.serialization ships as its own jars. MIT, BSD-3-Clause and
    // Apache-2.0 all require the copyright notice and licence text to be preserved *on redistribution* — and a
    // file sitting in the Git repository does not accompany the binary a user installs from the Marketplace.
    // Copying them into the jar's resources is what actually discharges the obligation.
    //
    // Kept as a build step rather than a checked-in copy under `src/main/resources/`, so the notices cannot
    // drift out of sync with the files they describe: one source of truth at the repository root, packaged at
    // build time. `THIRD-PARTY-NOTICES.md` is surfaced to the user by the About dialog (see InfoDialogs).
    processResources {
        from(rootProject.file("THIRD-PARTY-NOTICES.md")) { into("META-INF") }
        from(rootProject.file("LICENSE")) { into("META-INF") }
        from(rootProject.file("LICENSES")) { into("META-INF/licenses") }
    }
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
        useJUnitPlatform {
            includeTags("driftLive")
            includeEngines("junit-jupiter")
        }
        // Belt-and-suspenders: restrict discovery to the drift package.
        filter { includeTestsMatching("dev.lain.claudejb.drift.*") }
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        // Always re-run (it polls the network + binary); never serve a cached result.
        outputs.upToDateWhen { false }
        val binaryPath = (
            providers.gradleProperty("claudeBinary").orNull
                ?: providers.environmentVariable("CLAUDE_BINARY").orNull
                ?: "${System.getProperty("user.home")}/.local/bin/claude"
        )
        systemProperty("claudejb.drift.projectDir", rootProject.projectDir.absolutePath)
        systemProperty(
            "claudejb.drift.sdkDir",
            rootProject.file("node_modules/@anthropic-ai/claude-agent-sdk").absolutePath,
        )
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
            // Ceiling widened to 263.* ahead of the 2026.3 EAP: the API is stable and clean across 251→262
            // (all Compatible, zero deprecations), so we declare the next branch preemptively. verifyPlugin's
            // select block (below) already reaches 263.* and will verify against a real 263 build as soon as one
            // ships — until then it resolves to the latest 262 EAP, which is Compatible.
            sinceBuild = "251"
            untilBuild = "263.*"
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

        // The "zero deprecations" rule, ENFORCED rather than merely written down.
        //
        // The plugin's default failure level is COMPATIBILITY_PROBLEMS + INTERNAL_API_USAGES +
        // OVERRIDE_ONLY_API_USAGES — deprecated usages are only REPORTED. So this repo's stated policy
        // ("never ship a deprecated or scheduled-for-removal API — treat it as a blocker, not a warning")
        // was a promise a human had to keep by reading logs, and a rule that lives only in prose is not a
        // rule. Adding DEPRECATED_API_USAGES is what makes the sentence true.
        //
        // EXPERIMENTAL_API_USAGES is deliberately NOT here, and that is a decision rather than an oversight:
        // `DiffTabCleanup` uses `ProjectCloseListener.projectClosingBeforeSave` knowingly, because it is the
        // only hook that runs BEFORE the workspace state is written — which is the whole point of it. An
        // experimental API is acceptable with a reason; a deprecated one is not acceptable at all, because
        // it has an announced removal date and the plugin has to keep working across the IDE range.
        failureLevel =
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
                VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
                VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES,
            )
        ides {
            // No hardcoded path in the repo: a developer can point the verifier at local IDE installs to skip the
            // downloads, via -PlocalIdePath=<dir>[,<dir>…] or the LOCAL_IDE_PATH env var (comma-separated). This is
            // what makes an OFFLINE verification of the whole declared range possible — download.jetbrains.com is
            // not always reachable from every network, and the verifier is the only thing that catches a *binary*
            // incompatibility (see InstalledPlugins: `PluginId` is a Kotlin class since 2025.2, so `PluginId.getId`
            // compiles fine and then dies with NoSuchFieldError on 242–251).
            // When unset/missing (or on CI), fall back to recommended() — which spans the plugin's whole declared
            // range including the since-build FLOOR, the gate that catches a too-new API.
            val localIdes =
                (
                    providers.gradleProperty("localIdePath").orNull
                        ?: providers.environmentVariable("LOCAL_IDE_PATH").orNull
                )?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.map { file(it) }
                    ?.filter { it.exists() }
                    .orEmpty()
            val offline = localIdes.isNotEmpty() && !providers.environmentVariable("CI").isPresent
            if (offline) {
                // OFFLINE mode: the given installs are the ENTIRE set to verify against. Neither recommended()
                // nor select() may run here — both resolve through download.jetbrains.com, so leaving either in
                // made `-PlocalIdePath` a lie: it added local IDEs but still downloaded, and on a network that
                // truncates a 1.6 GB transfer the task failed before verifying anything. The flag's whole
                // purpose is verification WITHOUT the CDN, so in this mode there is nothing to download.
                localIdes.forEach { local(it) }
            } else {
                // Online (CI, or no local installs): recommended() spans the plugin's whole declared range
                // including the since-build FLOOR — the gate that catches a too-new API — and select() adds the
                // NEWEST EAP/RC. The range upper bound (263.*) matches the declared untilBuild, widened
                // preemptively because the API is clean across 251→262; until a 2026.3/263 EAP ships this
                // resolves to the latest 262 build, and picks up a real 263 automatically once one exists.
                recommended()
                select {
                    types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                    channels = listOf(ProductRelease.Channel.EAP, ProductRelease.Channel.RC)
                    sinceBuild = "262"
                    untilBuild = "263.*"
                }
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

// --- Static analysis and formatting --------------------------------------------------------------------
// Two tools because they answer different questions, and conflating them is how projects end up arguing
// about braces in code review: Spotless/ktlint decides how the code LOOKS (mechanical, never a judgement
// call), detekt decides whether it is likely WRONG (complexity, swallowed errors, suspicious constructs).
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    // Set unconditionally: `detektBaseline` needs the path as an OUTPUT (it is the file it writes), so making
    // it conditional on the file already existing makes generating it for the first time impossible. The
    // `detekt` task tolerates the file being absent.
    baseline = file("config/detekt/baseline.xml")
    // Analyse main and test alike. A test that swallows an exception hides a defect just as effectively as
    // production code doing it — arguably more so, because it does it while claiming to prove correctness.
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        sarif.required.set(true) // consumable by GitHub code scanning if we ever want the findings inline
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
}

// ---------------------------------------------------------------------------
// Coverage gates — per package, because one global number would be a lie either way.
//
// The honest shape of this codebase is that its risk is NOT evenly distributed. `permission/` decides whether
// the agent may read your SSH key; `ui/` paints a browser. A single global threshold either sets the bar so low
// that the guard could rot unnoticed, or so high that it can only be met by writing tests against Swing and
// JCEF that assert nothing anyone cares about. So the bar is per package, and it is set slightly BELOW what
// each package measures today: a gate that catches regression, not a target that invites test-padding.
//
// `ui`/`ui.jcef` are excluded rather than gated at a token value. They need a live IDE and a live Chromium, and
// they are covered by a different layer entirely: 54 vitest tests drive the real shipped JS, and the release
// checklist requires a manual pass through the UI. Excluding them says that out loud; gating them at 20% would
// dress the same fact up as a passing check.
//
// Measured 2026-08-05 (line coverage): permission 98.1 · protocol 87.3 · settings 86.1 · diff 72.8 ·
// session 67.3 · context 42.1 · process 37.9 · ui.jcef 31.2 · ui 24.6 · TOTAL 53.3.
// `context`/`process` are ungated for now: they wrap the OS (clipboard, process spawn, shell env) and most of
// what is uncovered there cannot run in CI. That is a known gap, not an endorsement.
// ---------------------------------------------------------------------------
kover {
    // `checkDrift` must NOT be dragged into the coverage graph.
    //
    // Kover instruments and aggregates EVERY `Test` task in the project, and `checkDrift` is registered as one.
    // That silently made it a dependency of `koverVerify`, so the `Static analysis` CI job ran the on-demand
    // drift check — which downloads the latest SDK and probes a LOCALLY INSTALLED `claude` binary. There is no
    // such binary on a runner, so it died with an IOException and failed the job.
    //
    // It passed locally, which is the whole lesson: the maintainer's machine has the binary, so the difference
    // between "this task is on-demand" and "this task is wired into check" was invisible until CI ran it. The
    // task's own KDoc already said "NOT wired into `check`" — it just was not true of the coverage graph.
    currentProject {
        instrumentation {
            disabledForTestTasks.add("checkDrift")
        }
    }
    reports {
        filters {
            excludes {
                // Need a live IDE / live Chromium to execute at all. Covered instead by the 54 vitest tests
                // that drive the REAL shipped JS, and by the manual UI pass the release checklist requires.
                classes("dev.lain.claudejb.ui.*")
                // Thin IDE-action shells: their bodies are one delegate call each, and exercising them means
                // booting an IDE to assert that a menu item calls a method.
                classes("dev.lain.claudejb.actions.*")
                // Wrappers over the OS — system clipboard, process spawn, shell environment. Most of what is
                // uncovered here cannot run on a CI box at all. A KNOWN GAP, listed so it is not mistaken for
                // coverage; the parts that are pure (AttachmentEncoder, EnvScriptLoader.parse) are tested.
                classes("dev.lain.claudejb.context.*", "dev.lain.claudejb.process.*")
                // A single line delegating to PluginManager.isPluginInstalled. It exists precisely BECAUSE it
                // must run against a real platform (PluginId is a Kotlin class since 2025.2, so the naive call
                // dies with NoSuchFieldError below 252) — which is also why a unit test cannot exercise it.
                classes("dev.lain.claudejb.util.*")
            }
        }
        verify {
            // NB: Kover 0.9.2's KoverVerifyRule has no per-rule `filters` (verified against the plugin jar), so
            // the per-package thresholds this project wants — permission ≥95, protocol ≥80, session ≥65 — are
            // not expressible one-by-one. What IS expressible is a FLOOR applied to every package
            // individually, plus an aggregate. Both are real gates: the floor catches any single package
            // collapsing, the aggregate catches death by a thousand cuts. The tighter per-package bars remain
            // the intent; see docs/RELEASE_CHECKLIST.md.
            rule("every gated package holds its floor") {
                groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.PACKAGE
                minBound(65)
            }
            rule("gated code as a whole") {
                minBound(75)
            }
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(
            mapOf(
                // The codebase reads at ~120 columns and has done for its whole life; reflowing 13k lines to
                // ktlint's default would be a huge diff that buys nothing.
                "max_line_length" to "140",
                // Trailing commas stay: they are why adding a parameter touches one line instead of two.
                "ij_kotlin_allow_trailing_comma" to "true",
                "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
                // function-signature off. Its only effect here was to COLLAPSE multi-line parameter lists back
                // onto one line because they now fit in 140 columns — which trades away the thing the
                // multi-line + trailing-comma style buys: adding a parameter is a one-line diff, not a reflow
                // of the whole signature. The Kotlin conventions endorse trailing commas for exactly that
                // reason and do not require collapsing a signature that happens to fit.
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_class-signature" to "disabled",
                "ktlint_standard_function-expression-body" to "disabled",
                // ── One owner per rule ──────────────────────────────────────────────────────────────────
                // Below, ktlint duplicates a rule detekt also enforces, and only detekt can scope itself to a
                // source set. Running both means the stricter-but-blinder one decides, which is how you end up
                // reformatting test fixtures to satisfy a tool that cannot be told they are fixtures. So each
                // of these has exactly one owner, and it is the one that can express the exception:
                //
                // max-line-length → detekt MaxLineLength (excludes the test tree: single-line raw-string
                //   protocol fixtures, one NDJSON frame each, exactly as the binary emits them).
                // function-naming → detekt FunctionNaming (excludes the test tree: test methods are
                //   backtick-quoted sentences, which is why a failure report reads like a sentence).
                //
                // Production code is still covered for both — by detekt, at the same strictness as before.
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
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
    val end =
        lines.drop(start + 1).indexOfFirst { it.startsWith("## v") }.let {
            if (it < 0) lines.size else start + 1 + it
        }

    fun inline(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("`(.+?)`"), "<code>$1</code>")

    val html = StringBuilder()
    var inList = false

    fun closeList() {
        if (!inList) return
        html.append("</ul>")
        inList = false
    }

    for (raw in lines.subList(start, end)) {
        val line = raw.trim()
        when {
            line.startsWith("## v") -> {
                html.append("<p><b>").append(inline(line.removePrefix("## ").trim())).append("</b></p>")
            }

            line == "---" || line.isEmpty() -> {
                closeList()
            }

            line.startsWith("- ") -> {
                if (!inList) {
                    html.append("<ul>")
                    inList = true
                }
                html.append("<li>").append(inline(line.removePrefix("- ").trim())).append("</li>")
            }

            else -> {
                closeList()
                html.append("<p>").append(inline(line)).append("</p>")
            }
        }
    }
    closeList()
    return html.toString()
}
