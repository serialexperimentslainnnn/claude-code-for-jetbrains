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
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // Static analysis (detekt) and formatting (ktlint via Spotless). Added in 5.0.0: until then the whole
    // quality bar rested on review, which is exactly the thing the standards say to mechanise — "if format
    // is being discussed in a review, a formatter is missing".
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.diffplug.spotless") version "8.9.0"
}

group = "dev.lain"
version = "5.8.0"

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
        // Compile against IntelliJ IDEA Community 2025.3 — the declared since-build floor (253), so we build
        // against the oldest IDE we support (never below it) and the plugin still loads in newer IDEs because
        // untilBuild is widened below.
        //
        // Raised from 2025.2 together with the floor: `com.intellij.modules.jcef`, which the descriptor now
        // declares, does not exist in 252 at all — compiling against an IDE that cannot satisfy the plugin's
        // own dependencies makes `runIde` a sandbox the plugin refuses to load in, and the "build against the
        // floor" rule stops meaning anything.
        // By BUILD NUMBER, not "2025.3.1": that marketing version is not published in the Maven repository the
        // plugin resolves from (only the point releases are), so the plain name fails to resolve.
        // `useInstaller = false` resolves the Maven artifact instead of the `.tar.gz` installer — smaller, and
        // it carries everything this build needs, `com.intellij.modules.jcef` included (checked in the
        // artifact's own `lib/product-backend.jar`).
        // NOT `IntellijIdeaCommunity`: the `ideaIC` artifact stopped being published at 2025.3 (253) — the very
        // floor this release moved to — and the Gradle plugin warns about it on every build. JetBrains ships a
        // single unified IDEA distribution from 253 onwards, which is what `intellijIdea(…)` resolves.
        //
        // **253.29346.138 (2025.3.1), not 253.28294.334 (2025.3) — and the ten days between them are the whole
        // point.** `com.intellij.modules.jcef`, which `plugin.xml` declares a MANDATORY dependency on, does not
        // exist in 2025.3: its `product-backend.jar` carries 38 `com.intellij.modules.*` aliases and none of
        // them is that one. It appears in 2025.3.1 — 39 aliases, the extra one being exactly `jcef`. So on
        // 2025.3 the IDE refuses to load this plugin outright ("has dependency on 'com.intellij.modules.jcef'
        // which is not installed"), which is the same failure that made 5.1.1 dead on 2026.2, at the other end
        // of the range. The dependency cannot simply be dropped: from 262 JCEF is a bundled plugin, and without
        // declaring it the plugin's classloader has no `com.intellij.ui.jcef.*` at all. It is mandatory from
        // the build that has it and impossible before — so the FLOOR moves, and `sinceBuild` below moves with
        // it. (On 2025.3 itself `JBCefApp` does live in `lib/app.jar`, i.e. everything compiles and 5.1.1 ran
        // there happily; it is the declaration that cannot be satisfied, not the classes that are missing.)
        intellijIdea("253.29346.138") {
            useInstaller = false
        }
        // Bundled IDE Terminal: used to open an interactive `claude login` session (the OAuth flow needs a
        // TTY, which the stream-json process doesn't have). Compile-only coupling; TerminalLauncher guards
        // its use behind PluginManager.isPluginInstalled so a disabled Terminal plugin degrades gracefully.
        bundledPlugin("org.jetbrains.plugins.terminal")
        // Bundled Git plugin: compile-only coupling for `git4idea.*` (GitRepositoryManager, GitHistoryUtils),
        // read-only. Declared OPTIONAL in META-INF/plugin.xml (config-file claude-git.xml) — unlike JCEF, an IDE
        // without Git, or a project that is not a working copy, must still load the plugin; `GitGateway` is the
        // only file that names a git4idea type and it is never reached unless `GitAvailability` says yes.
        bundledPlugin("Git4Idea")
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
        // The distributed map is written FOR this repository and lands in the artifact by accident: the one
        // under `src/main/resources/jcef/` is a resource like any other, so it shipped inside the plugin jar —
        // 20 KB of internal design notes and source paths handed to every user, for nothing. Excluded by
        // pattern rather than by path, because the next directory to get a map will be a `resources` one again
        // and nobody will think of this file. Nothing reads it at runtime: `JcefHost` names every script and
        // stylesheet it loads explicitly (`appNames`, `CSS_PARTS`) and globs no resource directory.
        exclude("**/PROJECTMAP.md")
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

    // NB there is deliberately NO `checkProjectMap` task, and the `PROJECTMAP.md` files are not gated.
    //
    // They are an orientation index for AI-assisted sessions — a local tooling convention, not part of the
    // product: nothing in them reaches the artifact, and `processResources` excludes them from it. Gating the
    // build on them made the one check whose failure can never be a defect in the plugin, and imposed a
    // Python script on anyone who clones the repository and edits a file. Regenerate with
    // `python3 scripts/gen-projectmap.py` when you want them current.

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
        // RemoteRobot needs a running IDE on a display, and this task starts neither, so the flag is an
        // acknowledgement that both are already up. It is asserted rather than used as an `onlyIf`: a Gradle
        // skip is `BUILD SUCCESSFUL` with zero tests executed, which is the one outcome a verification task
        // must never produce. `uiTest` hangs off no aggregate task (see `check` below), so the only way to
        // reach this is to ask for it by name — and asking for it without the flag is a mistake worth a red.
        doFirst {
            if (project.findProperty("uiTest.enabled") != "true") {
                throw GradleException(
                    "uiTest needs an IDE already running with robot-server on a display, and does not start " +
                        "one. Boot it with `./gradlew runIdeForUiTests` (under xvfb-run if headless), then " +
                        "re-run this task with -PuiTest.enabled=true.",
                )
            }
        }
    }

    // The uiTest source set inherits the test classpath, so the sandbox-project fixture can be contributed
    // from more than one resource root; tolerate the duplicate deterministically instead of failing the copy.
    named<Copy>("processUiTestResources") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // `check` already depends on `test`, which now includes the headless/integration packages.
    //
    // `uiTest` is kept out of TWO graphs, and both are needed — enumerating one and stopping there invites the
    // conclusion that it is enough, which it is not:
    //   1. out of `check`, because it needs a display and an already-running IDE, so it runs nightly or by
    //      hand and would otherwise fail every ordinary `check`;
    //   2. out of Kover's report graph, via `disabledForTestTasks` in the `kover { }` block below — a `Test`
    //      task is pulled in as a dependency of `koverXmlReport`/`koverVerify` whether or not `check` wants
    //      it, so staying out of `check` alone leaves the coverage tasks unable to run anywhere the IDE is
    //      not already up.
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
                    // THE WHOLE UI IS A BROWSER, and this is what lets the suite talk to it. Not a magic
                    // number: `ide.browser.jcef.jsQueryPoolSize` is a platform REGISTRY key — `JBCefClient`
                    // reads it once via `RegistryManager.intValue(...)` into `JS_QUERY_POOL_DEFAULT_SIZE`, and
                    // a registry value falls back to the system property of the same name (verified in the
                    // bytecode of `RegistryValue`/`JBCefClient` in the IDE distribution), so a `-D` on the
                    // IDE's command line IS how it is set.
                    // A `JBCefJSQuery` can only be attached to a browser that has ALREADY loaded if its slot
                    // was reserved when the client was created; with the pool at its default the platform
                    // refuses with "Set the property JBCefClient.Properties.JS_QUERY_POOL_SIZE to use
                    // JBCefJSQuery after the browser has been created". That is exactly what JetBrains'
                    // `JCefBrowserFixture` does, and it is the only route src/uiTest has into the DOM — so
                    // without this line every DOM-driving test fails at fixture construction, before it can
                    // assert anything. 10000 is the size the fixture's own documented precondition asks for
                    // (recorded again in `UiTestBase`'s KDoc); it costs reserved callback slots in a
                    // throwaway sandbox IDE and nothing else. Do not "tidy" it away.
                    "-Dide.browser.jcef.jsQueryPoolSize=10000",
                    // Quiet, deterministic first run: no privacy/consent gates, no tips, no "what's new".
                    "-Djb.privacy.policy.text=<!--999.999-->",
                    "-Djb.consents.confirmation.enabled=false",
                    "-Dide.show.tips.on.startup.default.value=false",
                    "-Dide.mac.message.dialogs.as.sheets=false",
                    "-Dide.mac.file.chooser.native=false",
                    "-DjbScreenMenuBar.enabled=false",
                    "-Dapple.laf.useScreenMenuBar=false",
                    // Auto-trust opened projects so no "Trust this project?" modal blocks the robot. The key is
                    // `idea.` — the neighbour above is `ide.` because it is a platform REGISTRY key, and this one
                    // is a system property read by `TrustedProjects` via `Boolean.getBoolean`. The two
                    // namespaces sit ten lines apart, so the wrong prefix reads as consistent with its
                    // neighbour: verified against the 253 distribution, where `ide.trust.all.projects` appears
                    // nowhere. Under Xvfb there is a real display, so the headless escape hatch
                    // (`idea.trust.headless.disabled`) never fires and the modal has nothing to dismiss it.
                    "-Didea.trust.all.projects=true",
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
            // Floor 253 (2025.3), raised from 251 in 5.5.0 — and it is JCEF that raises it, not an API tidy-up.
            //
            // Since 262 the platform ships the embedded browser as a separate bundled plugin, so a plugin that
            // wants `com.intellij.ui.jcef.*` in its classloader must declare `com.intellij.modules.jcef` (see
            // META-INF/plugin.xml). That id does not exist on 251/252 — verified in the IDE distributions
            // themselves: on 251/252 `JBCefApp` sits in `lib/app-client.jar` and nothing declares the module;
            // on 253 and 261 the platform declares `<module value="com.intellij.modules.jcef"/>` in
            // `product-backend.jar`; on 262 it is the plugin. Declaring it therefore costs 2025.1 and 2025.2,
            // and the alternative was leaving the plugin DEAD on 2026.2 — the whole UI is that browser, so
            // there is nothing to degrade to.
            //
            // (The previous floor note still holds for the API: `FileChooserDescriptorFactory.multiFiles()`
            // does not exist before 251. It is simply no longer the binding constraint.)
            //
            // Ceiling 263.*: declared ahead of the 2026.3 branch on purpose, so an EAP user is never locked out
            // by a range we forgot to widen. It is not a guess — `verifyPlugin` verifies against the EAP and RC
            // channels up to that bound (see the `select` block below), so the claim is checked on every run.
            // 253.29346.138 = IntelliJ IDEA 2025.3.1, the FIRST build that ships
            // `com.intellij.modules.jcef` — the mandatory dependency this plugin declares. 2025.3 itself
            // (253.28294.334, ten days earlier) does not have it, and there the IDE refuses to load the plugin
            // at all. A floor of plain "253" was therefore a promise that could not be kept for the first
            // release of that branch; see the platform declaration above for the full reasoning.
            sinceBuild = "253.29346.138"
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
        //
        // MISSING_DEPENDENCIES is here for a reason found the hard way, and it is the most load-bearing entry
        // in this list: a mandatory `<depends>` that the target IDE cannot satisfy means **the plugin does not
        // load at all** — not a degraded feature, not a warning, nothing. The verifier detects it perfectly
        // (pointed at 253.28294.334 it says "1 missing mandatory dependency" in as many words) and, without
        // this line, still finished with BUILD SUCCESSFUL. A gate that finds the fault and passes anyway is
        // worse than no gate: it is a green tick over a plugin that cannot start.
        failureLevel =
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
                VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
                VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES,
                VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
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
                // THE DECLARED FLOOR, PINNED BY BUILD NUMBER — and this line exists because its absence cost a
                // release. The comment below used to claim that `recommended()` covers "the since-build floor";
                // it does not. `recommended()` returns JetBrains' recommended set, which for 253 resolves to
                // the LAST point release (253.33813.55), never the first. So the one build the plugin promises
                // to support and is most likely to break on — the oldest — was the only one never verified,
                // and `com.intellij.modules.jcef` missing from 2025.3 went unnoticed through every green run.
                // Keep this in step with `sinceBuild` above: they are the same claim, stated twice, and a gate
                // that drifts from the promise it checks is not a gate.
                // `useInstaller = false` for the same reason the compile platform above uses it: the CDN has no
                // `.tar.gz` named after a build number (`ideaIU-253.28294.334.tar.gz` → 404), only the Maven
                // artifact carries one, and pinning the exact build is the entire point of this entry.
                create(IntelliJPlatformType.IntellijIdea, "253.29346.138") {
                    useInstaller = false
                }
                // Online (CI, or no local installs): recommended() adds JetBrains' recommended spread across
                // the declared range, and select() adds the NEWEST EAP/RC. The upper bound matches the declared untilBuild; as of Aug 2026 the newest
                // build on either channel is 262.9437.65 (2026.2.1 RC), so this resolves there today and picks
                // up a real 263 automatically the day one ships.
                //
                // BOTH families, not just IDEA. The plugin is used in PyCharm as much as in IDEA, and the
                // packaging differences between products are exactly where a classloader problem hides — 5.1.1
                // shipped unusable on 2026.2 because JCEF moved into a bundled plugin there, and verifying one
                // product tells you nothing about how another bundles the same platform.
                recommended()
                select {
                    types =
                        listOf(
                            IntelliJPlatformType.IntellijIdeaCommunity,
                            IntelliJPlatformType.PyCharmCommunity,
                        )
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
// Coverage gates. The INTENT is per package, because one global number would be a lie either way; what the
// tool can actually enforce is a floor plus an aggregate, and the `verify` block below says why.
//
// The honest shape of this codebase is that its risk is NOT evenly distributed. `permission/` decides whether
// the agent may read your SSH key; `ui/` paints a browser. A single global threshold either sets the bar so low
// that the guard could rot unnoticed, or so high that it can only be met by writing tests against Swing and
// JCEF that assert nothing anyone cares about. So the exclusions below say which packages are not gated at
// all, and the rules say what the gated ones must hold — each bound sitting slightly BELOW what is measured,
// so it is a gate that catches regression rather than a target that invites test-padding.
//
// `ui`/`ui.jcef` are excluded rather than gated at a token value. They need a live IDE and a live Chromium, and
// they are covered by a different layer entirely: the vitest suite drives the real shipped JS (`npm test`
// reports how many, and that is the only honest way to say it — a count written here ages on its own, in
// silence, with nobody to notice), and the release checklist requires a manual pass through the UI. Excluding
// them says that out loud; gating them at 20% would dress the same fact up as a passing check.
//
// `context`/`process` are ungated: they wrap the OS (clipboard, process spawn, shell env) and most of what is
// uncovered there cannot run in CI. That is a known gap, not an endorsement.
//
// The measured figures live in ONE place — docs/RELEASE_CHECKLIST.md §Coverage policy — beside the exclusion
// list this block has to agree with. They are deliberately not repeated here: a measurement written into two
// files is a measurement that will disagree with itself, and this file has no way to notice when it does.
// ---------------------------------------------------------------------------
kover {
    // Kover aggregates EVERY `Test` task in the project, so a task that is on-demand everywhere else is still
    // pulled into the coverage graph and becomes a dependency of `koverXmlReport`/`koverVerify`. Being absent
    // from `check` does not keep a task out of this one: it has to be named here.
    //
    // `checkDrift` is registered as a `Test` task and downloads the latest SDK and probes a LOCALLY INSTALLED
    // `claude` binary, which a CI runner does not have.
    currentProject {
        instrumentation {
            disabledForTestTasks.add("checkDrift")
            // `uiTest` is the same shape and must be out for two independent reasons. It is a `Test` task
            // (registered above), so it lands in the dependency graph of `koverXmlReport`/`koverVerify` — and
            // it drives an ALREADY-RUNNING IDE over HTTP, asserting `-PuiTest.enabled=true` rather than
            // skipping, so without this line neither report can be produced anywhere that IDE is not already
            // up on a display. Its coverage would also be empty either way: the code it exercises runs in that
            // other IDE process, which this build never instruments.
            disabledForTestTasks.add("uiTest")
        }
    }
    reports {
        filters {
            excludes {
                // Need a live IDE / live Chromium to execute at all. Covered instead by the vitest suite,
                // which drives the REAL shipped JS (`npm test` is what counts it), and by the manual UI pass
                // the release checklist requires.
                classes("dev.lain.claudejb.ui.*")
                // Thin IDE-action shells: their bodies are one delegate call each, and exercising them means
                // booting an IDE to assert that a menu item calls a method.
                classes("dev.lain.claudejb.actions.*")
                // Wrappers over the OS — system clipboard, process spawn, shell environment. Most of what is
                // uncovered here cannot run on a CI box at all. A KNOWN GAP, listed so it is not mistaken for
                // coverage; the parts that are pure ARE tested — `ClipboardCli`/`ImageAttachments` in `context/`
                // (ClipboardCliTest, ImageAttachmentsTest) and `EnvScriptLoader.parse` in `process/`. Those
                // names are load-bearing: a comment citing a file that no longer exists is worse than none.
                classes("dev.lain.claudejb.context.*", "dev.lain.claudejb.process.*")
                // The Git integration's IDE-bound half: the availability probe (asks the running IDE's plugin
                // set), the git4idea gateway (spawns `git log` through the platform) and the hand-off to the
                // Version Control tool window. Exercising any of them means a live IDE AND a real repository on
                // disk, which is exactly the headless/integration test this package deliberately does not have.
                // `GitCommitInfo` — the pure half, and the only place a bug would be silent — is NOT excluded:
                // it stays gated and is covered by GitCommitInfoTest. The read-only and API contracts are
                // pinned by source/reflection tests instead (GitReadOnlyContractTest, GitApiContractTest).
                //
                // The trailing `*` is not decoration. `GitGateway.refs()` sorts with
                // `compareByDescending {}.thenBy {}`, and each of those compiles to a SYNTHETIC class of its
                // own (`GitGateway$refs$$inlined$thenBy$1` and friends) that an exact-name pattern does not
                // match. A lambda added inside an excluded object would otherwise start counting against the
                // package's floor, which reads as coverage erosion in code that was never gated.
                classes(
                    "dev.lain.claudejb.git.GitAvailability*",
                    "dev.lain.claudejb.git.GitGateway*",
                    "dev.lain.claudejb.git.GitHistoryService*",
                    "dev.lain.claudejb.git.GitLogNavigator*",
                )
                // A single line delegating to PluginManager.isPluginInstalled. It exists precisely BECAUSE it
                // must run against a real platform (PluginId is a Kotlin class since 2025.2, so the naive call
                // dies with NoSuchFieldError below 252) — which is also why a unit test cannot exercise it.
                classes("dev.lain.claudejb.util.*")
                // The vulnerability view's two platform-bound halves, excluded on the same grounds as
                // `process.*` and `ui.*` above and NOT as a blanket on the package: `OsvHttp` is a java.net.http
                // wrapper whose every branch needs a live socket, and `VulnService` is a project `@Service` that
                // needs a Project, the pooled thread and the EDT. `OsvScanner` is deliberately NOT excluded —
                // it talks to OsvHttp through a plain call and its gap is real debt, so it stays gated and
                // visible rather than being defined out of the measurement.
                classes("dev.lain.claudejb.vuln.OsvHttp*", "dev.lain.claudejb.vuln.VulnService*")
            }
        }
        verify {
            // `KoverVerifyRule` has no per-rule `filters` — re-checked at 0.9.9, the version this build
            // resolves, against the plugin's own DSL sources: a rule exposes `groupBy`, `disabled` and its
            // bounds, and filters exist on the report set, never on a rule. A report variant is no substitute
            // either, because a variant is scoped by source set and not by package. So a threshold per package
            // cannot be written. What CAN be written is a FLOOR applied to every package on its own, plus an
            // AGGREGATE over all gated code, and the two see different failures: the floor catches one package
            // collapsing, the aggregate catches erosion spread too thin for any single package to show it.
            //
            // Each rule carries a line bound and a branch bound, because they answer different questions. A
            // line bound says the code RAN. A branch bound says the decision was taken BOTH ways — and in
            // `permission/` and `session/` a branch never taken is a security decision never exercised: the
            // guard's deny path, the admission fixpoint's rejection. That code reaches high line coverage
            // while never once having said no, which is exactly what a line bound cannot see.
            //
            // The branch floor is much lower than the line floor because a floor is fixed by the weakest
            // package, and on branches the weakest is far below the rest. It is therefore a collapse detector,
            // not a regression detector — and the aggregate cannot stand in for it, because a small package
            // carries too little of the branch mass to move the total: `permission/` could lose half its
            // branch coverage without the aggregate reaching its bound. The floor is the only bound here that
            // looks at a package on its own.
            //
            // Both files must agree, and the measured figures every bound sits below live only in
            // docs/RELEASE_CHECKLIST.md §Coverage policy.
            rule("every gated package holds its floor") {
                groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.PACKAGE
                minBound(65)
                minBound(20, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
            rule("gated code as a whole") {
                minBound(75)
                minBound(40, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
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
