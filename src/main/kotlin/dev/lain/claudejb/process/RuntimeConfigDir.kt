package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import dev.lain.claudejb.settings.SecretStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * A throwaway `CLAUDE_CONFIG_DIR` **in RAM**, so the binary can read its own credentials file without that
 * file ever existing on persistent storage.
 *
 * This exists because of a measured, non-negotiable fact about the binary (verified against 2.1.223 with the
 * plugin's exact launch arguments): authenticating through `CLAUDE_CODE_OAUTH_TOKEN` gives a REDUCED
 * identity. Same account, same moment, same argv, two runs:
 *
 * ```
 * with its own file  -> subscription_type "max", rate_limits_available true, five_hour 11%, seven_day 94%
 * with the env token -> subscription_type null,  rate_limits_available false, rate_limits null
 * ```
 *
 * So the dashboard's plan limits, plan name and account are not something the plugin can compute or recover:
 * the binary only reports them when it authenticates from a config directory. That directory is what this
 * class provides, and it puts it on **tmpfs** (`/dev/shm`) — memory, wiped on reboot, never written to a
 * disk — with the directory 0700 and the credential 0600.
 *
 * Everything else is a SYMLINK back into the real `~/.claude` (session history, settings, skills, plugins,
 * projects) plus `~/.claude.json`, so relocating the config directory costs nothing: `/resume`, the session
 * list and the user's own configuration keep working, and the account profile in `.claude.json` is still
 * found — which is why the email and organization come back with it.
 *
 * The binary REWRITES `.credentials.json` here when it refreshes the token, and that is the second thing
 * this buys: [collect] takes the refreshed credential back into the safe at teardown, so a long-lived login
 * survives instead of expiring into a sign-in card.
 */
object RuntimeConfigDir {

    private val log = thisLogger()

    const val ENV_NAME = "CLAUDE_CONFIG_DIR"

    private const val CREDENTIALS = ".credentials.json"

    /** tmpfs mounts, in preference order. Everything here is RAM-backed and gone at reboot. */
    private val RAM_ROOTS = listOf("/dev/shm", "/run/user/${runCatching { osUid() }.getOrDefault("")}")

    private fun osUid(): String = runCatching {
        com.sun.security.auth.module.UnixSystem().uid.toString()
    }.getOrDefault("")

    /**
     * The RAM root to build under, or null when this platform has none.
     *
     * Deliberately null rather than "fall back to a temp directory": a temp directory is ordinary disk, and
     * silently writing the credential there would be exactly the thing this avoids, while looking like it
     * had not happened. Callers fall back to the env token — reduced dashboard, nothing on disk.
     */
    private fun ramRoot(): File? =
        RAM_ROOTS.asSequence().map(::File).firstOrNull { it.isDirectory && it.canWrite() }

    /** True when a RAM-backed config dir is possible here at all. */
    fun isAvailable(): Boolean = !SystemInfo.isWindows && ramRoot() != null

    /**
     * Builds the directory and returns it, or null when it cannot be built or the safe holds no credential.
     *
     * @param home the real `~` (overridable for tests, like [CredentialsVault.homeOverride]).
     */
    fun prepare(home: File = defaultHome()): File? {
        val blob = SecretStore.get(SecretStore.CREDENTIALS_JSON) ?: return null
        val root = ramRoot() ?: return null
        val dir = File(root, "claude-code-jb-${ProcessHandle.current().pid()}")
        return runCatching {
            dir.deleteRecursively()
            dir.mkdirs()
            restrict(dir, owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            linkRealConfig(home, dir)
            val creds = File(dir, CREDENTIALS)
            creds.writeText(blob)
            restrict(creds, owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            dir
        }.getOrElse {
            log.warn("could not build the RAM config dir; falling back to the env token", it)
            dir.deleteRecursively()
            null
        }
    }

    /**
     * Symlinks the real configuration in, so relocating the config dir does not hide the user's own history
     * and settings from the binary. `.credentials.json` is deliberately NOT linked — it is the one entry we
     * own, and linking it would point the binary straight back at the plaintext file we exist to avoid.
     */
    private fun linkRealConfig(home: File, dir: File) {
        val realConfig = File(home, ".claude")
        realConfig.listFiles()?.forEach { entry ->
            if (entry.name == CREDENTIALS) return@forEach
            runCatching { Files.createSymbolicLink(File(dir, entry.name).toPath(), entry.toPath()) }
        }
        // The account profile (email, organization) and the API-key approvals live here, NOT under ~/.claude.
        val profile = File(home, ".claude.json")
        if (profile.exists()) {
            runCatching { Files.createSymbolicLink(File(dir, ".claude.json").toPath(), profile.toPath()) }
        }
    }

    /**
     * Takes whatever the binary left in [dir] back into the safe, then removes the directory.
     *
     * The credential here may be NEWER than the one we wrote: the binary refreshes it in place. Collecting
     * it is what makes the login long-lived.
     */
    fun collect(dir: File?) {
        dir ?: return
        val creds = File(dir, CREDENTIALS)
        runCatching {
            creds.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let {
                SecretStore.set(SecretStore.CREDENTIALS_JSON, it)
            }
        }.onFailure { log.warn("could not collect the refreshed credential", it) }
        runCatching { dir.deleteRecursively() }.onFailure { log.warn("could not remove the RAM config dir", it) }
    }

    private fun restrict(file: File, owner: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(file.toPath(), owner) }
            .onFailure { log.warn("could not restrict ${file.name}", it) }
    }

    private fun defaultHome(): File =
        CredentialsVault.homeOverride ?: File(System.getProperty("user.home").orEmpty())
}
