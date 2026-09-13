package dev.lain.claudejb.controller.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.process.ClaudeBinaryLocator
import dev.lain.claudejb.controller.process.credentials.CredentialsVault
import dev.lain.claudejb.controller.session.auth.AuthGate
import dev.lain.claudejb.controller.session.auth.Credential
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.Provider
import dev.lain.claudejb.model.settings.SecretStore
import dev.lain.claudejb.model.settings.env.RemoteMounts
import dev.lain.claudejb.model.settings.env.resolveEnv
import java.io.File

class SessionLifecycle(
    private val s: ClaudeSession,
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
    fireAttention: (AttentionReason) -> Unit,
    onEvent: (ClaudeEvent) -> Unit,
) {

    private val process = SessionProcess(s, edt, fireState, fireAttention, onEvent)

    @Volatile private var starting = false

    @Volatile internal var ready = false

    @Volatile var disposed = false
        private set

    @Volatile internal var cachedEnv: Map<String, String>? = null

    @Volatile var binaryMissing: Boolean = false
        private set

    @Volatile var needsLogin: Boolean = false
        internal set

    val auth = AuthGate(
        project = project,
        signInInProgress = { s.login.inProgress },
        launchEnv = { effectiveLaunchEnv() },
        onProbed = { loggedIn ->
            when {
                !loggedIn -> onLoginNeeded()

                needsLogin -> {
                    needsLogin = false
                    edt { fireState() }
                }

                else -> edt { fireState() }
            }
        },
    )

    fun isRunning(): Boolean = process.isRunning()

    fun isStarting(): Boolean = starting

    fun write(line: String): Boolean = process.write(line)

    fun start(resume: Boolean): Boolean {
        if (disposed) return false
        if (isRunning() || starting) return true
        val settings = ClaudeSettings.getInstance(project)
        val binary = resolveBinary(settings) ?: return false
        if (!passesLaunchGates(settings)) return false
        when (auth.heldCredential(settings)) {
            Credential.NONE -> {
                onLoginNeeded()
                return false
            }

            Credential.UNKNOWN -> return true

            Credential.HELD -> Unit
        }
        val workDir = project.basePath?.let(::File) ?: File(System.getProperty("user.home"))

        ready = false
        s.catalog.initialized = false
        starting = true
        s.reconciler.onMessageBoundary()
        fireState()
        val launchGen = process.supersede()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launch(launchGen, settings, binary, workDir, resume)
            } finally {
                if (launchGen == process.generation) {
                    starting = false
                    edt { fireState() }
                }
            }
        }
        return true
    }

    internal fun onLoginNeeded() {
        needsLogin = true
        edt { fireState() }
        s.login.maybePrompt()
    }

    fun refreshBootState() {
        if (starting) return
        if (s.login.inProgress) return
        auth.absorbExistingLoginOnce()
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath)
        val missing = binary == null
        edt {
            if (missing && isRunning()) stop()
            if (missing != binaryMissing) {
                binaryMissing = missing
                fireState()
            }
        }
        if (binary == null) return
        if (settings.claudePath != binary.absolutePath) {
            settings.update { it.claudePath = binary.absolutePath }
        }
        val credentialed = auth.hasCredential(settings)
        edt {
            if (starting) return@edt
            when {
                !credentialed -> {
                    if (isRunning()) stop()
                    if (!needsLogin) onLoginNeeded()
                }

                !isRunning() -> s.start()
            }
        }
    }

    fun dismissLoginCard() {
        needsLogin = false
        edt { fireState() }
    }

    private fun resolveBinary(settings: ClaudeSettings): File? {
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run {
            binaryMissing = true
            fireState()
            s.notifier.missingBinary()
            return null
        }
        binaryMissing = false
        if (settings.claudePath != binary.absolutePath) {
            settings.update { it.claudePath = binary.absolutePath }
        }
        return binary
    }

    private fun passesLaunchGates(settings: ClaudeSettings): Boolean {
        if (!s.notifier.ensureExecTrust(settings)) return false
        if (RemoteMounts.isRemote(project.basePath)) {
            refuseRemoteProject(project.basePath)
            return false
        }
        return true
    }

    private fun refuseRemoteProject(root: String?) {
        val msg = SessionNotifier.remoteProjectRefusal(root)
        edt {
            s.transcript.add(Speaker.ERROR, msg)
            fireState()
        }
        s.notifier.error(msg)
    }

    internal fun effectiveLaunchEnv(base: Map<String, String>? = null): Map<String, String> {
        val env = base ?: ClaudeSettings.getInstance(project).resolveEnv()
        val settings = ClaudeSettings.getInstance(project)
        val apiKey = settings.anthropicApiKey
            .takeIf { it.isNotBlank() && settings.provider == Provider.ANTHROPIC && SecretStore.API_KEY !in env }
        val withSecrets = env +
            SecretStore.envOverlay(env.keys) +
            (apiKey?.let { mapOf(SecretStore.API_KEY to it) } ?: emptyMap())
        return withSecrets + CredentialsVault.envOverlay(withSecrets.keys)
    }

    private fun launch(launchGen: Int, settings: ClaudeSettings, binary: File, workDir: File, resume: Boolean) {
        if (!auth.renew(binary, settings)) {
            edt { onLoginNeeded() }
            return
        }
        val env = effectiveLaunchEnv(cachedEnv ?: settings.resolveEnv().also { cachedEnv = it })
        if (!process.spawn(launchGen, binary, workDir, env, resume)) return
        s.catalog.request()
        edt {
            ready = true
            s.transcript.add(Speaker.SYSTEM, "Claude Code ready.")
            auth.probe()
            fireState()
            s.poll.pollQuota()
            s.prompts.pump()
        }
    }

    fun restart(resume: Boolean) {
        stop()
        s.start(resume)
    }

    fun stop() {
        process.supersede()
        s.flushDeltas()
        s.poll.stopAll()
        s.turnControl.cancelPendingElicitations()
        process.terminate()
        s.turn.reset()
        ready = false
        s.catalog.initialized = false
        starting = false
        s.prompts.dropSuggestion()
        cachedEnv = null
        s.controlClient.failAll("process gone")
        s.taskTracker.clear()
        s.hookNarrator.clear()
        s.backgroundTaskRegistry.clear()
        s.agentScanner.clearTails()
        edt {
            s.cardManager.clear()
            s.diffs.clearReviewDiffs()
            fireState()
        }
    }

    fun shutdown() {
        disposed = true
        process.supersede()
        starting = false
        s.poll.stopAll()
        s.login.cancelLogin()
        s.turnControl.cancelPendingElicitations()
        s.diffs.clear()
        process.terminate()
        s.controlClient.failAll("process gone")
    }
}
