package dev.lain.claudejb.headless

import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.process.credentials.CredentialsVault
import dev.lain.claudejb.controller.session.SessionNotifier
import dev.lain.claudejb.controller.session.auth.AuthGate
import dev.lain.claudejb.controller.session.auth.Credential
import dev.lain.claudejb.controller.session.auth.DialogLoginUi
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.SecretStore
import dev.lain.claudejb.model.settings.SettingsStore
import java.io.File
import java.nio.file.Files

class AuthGateProbeHeadlessTest : BasePlatformTestCase() {

    private lateinit var home: File
    private val settings get() = ClaudeSettings.getInstance(project)
    private val fakeClaude: String get() = System.getProperty("claudejb.fakeClaude") ?: error("claudejb.fakeClaude is not set")

    private fun gate(signingIn: Boolean = false, onProbed: (Boolean) -> Unit = {}) =
        AuthGate(project, { signingIn }, { emptyMap() }, onProbed)

    override fun setUp() {
        super.setUp()
        home = Files.createTempDirectory("claudejb-home").toFile()
        CredentialsVault.homeOverride = home
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load(settings.scope)
        settings.replaceState(ClaudeSettings.State())
        settings.state.claudePath = fakeClaude
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
            SecretStore.storeOverride = null
            CredentialsVault.homeOverride = null
            home.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun waitForProbe(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("timed out waiting for the probe", condition())
    }

    fun `test with nothing vaulted the binary's own login decides, and the answer is cached`() {
        val gate = gate()
        assertEquals(Credential.UNKNOWN, gate.heldCredential(settings))
        assertTrue(gate.hasCredential(settings))
        assertEquals(Credential.HELD, gate.heldCredential(settings))
    }

    fun `test the probe asks the binary on our environment and reports what it said`() {
        var probed: Boolean? = null
        val gate = gate { probed = it }
        gate.probe()
        waitForProbe { probed != null }
        assertEquals(true, probed)
        assertNotNull(gate.status)
        assertTrue(gate.status!!.loggedIn)
    }

    fun `test renewal is skipped while signing in or when nothing needs renewing, and a rejected renewal is refused without a vault`() {
        val binary = File(fakeClaude)
        assertTrue(gate(signingIn = true).renew(binary, settings))
        assertTrue(gate().renew(binary, settings))
        assertFalse(gate(signingIn = true).renewRejected(binary, settings))
        assertFalse(gate().renewRejected(binary, settings))
        assertFalse(gate().canRenewCredential())
    }

    fun `test the startup harvest runs once`() {
        val gate = gate()
        gate.absorbExistingLoginOnce()
        gate.absorbExistingLoginOnce()
        assertEquals(Credential.UNKNOWN, gate.heldCredential(settings))
    }

    fun `test the dialog login ui hands the pasted code on, cancels on an empty answer, and reports a failure`() {
        val codes = ArrayList<String>()
        var cancelled = 0
        val ui = DialogLoginUi(project, SessionNotifier(project), { codes += it }, { cancelled++ })
        ui.onAuthUrl("https://claude.com/cai/oauth/authorize?x=1")
        TestDialogManager.setTestInputDialog { "  abc-123  " }
        ui.onCodeRequested()
        assertEquals(listOf("abc-123"), codes)
        TestDialogManager.setTestInputDialog { " " }
        ui.onCodeRequested()
        assertEquals(1, cancelled)
        ui.onLoginResult(false, "nope")
        ui.onLoginResult(true, "fine")
    }
}
