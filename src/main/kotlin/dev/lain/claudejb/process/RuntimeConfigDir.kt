package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import dev.lain.claudejb.settings.SecretStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * A throwaway `CLAUDE_CONFIG_DIR` for one session, so the binary can read its own credentials file without
 * that file existing on persistent storage.
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
 * class provides: a per-session temp dir, 0700, with the credential 0600, removed at teardown.
 *
 * **Be exact about what this does and does not claim.** The only PERSISTENT copy of the credential is the
 * encrypted safe; what lands here is a plaintext projection of it that exists while the session runs and is
 * owner-only. That is strictly better than `~/.claude/.credentials.json`, which is plaintext and permanent —
 * but it is not "the credential never touches a disk", and it was briefly special-cased onto `/dev/shm` in
 * pursuit of that phrasing. That bought a marginally shorter-lived file on ONE operating system in exchange
 * for the plugin behaving differently on the two most people use.
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

    /**
     * Builds the directory and returns it, or null when it cannot be built or the safe holds no credential.
     *
     * A per-session temporary directory, owner-only, deleted at teardown — the same on every platform. It
     * was briefly special-cased onto `/dev/shm`, which bought a marginally shorter-lived file on ONE
     * operating system in exchange for the plugin behaving differently on the two most people use. The
     * persistent memory for all of this is the encrypted safe; what lands here is a disposable projection of
     * it that exists only while the session runs.
     *
     * @param home the real `~` (overridable for tests, like [CredentialsVault.homeOverride]).
     */
    fun prepare(home: File = defaultHome()): File? {
        val blob = SecretStore.get(SecretStore.CREDENTIALS_JSON) ?: return null
        return runCatching {
            val dir = Files.createTempDirectory("claude-code-jb-").toFile()
            restrict(dir, owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            linkRealConfig(home, dir)
            val creds = File(dir, CREDENTIALS)
            creds.writeText(blob)
            restrict(creds, owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            dir
        }.getOrElse {
            log.warn("could not build the session config dir; falling back to the env token", it)
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
        // Symlinked when it exists, so the user's own approvals and settings come along.
        val profile = File(home, ".claude.json")
        if (profile.exists()) {
            runCatching { Files.createSymbolicLink(File(dir, ".claude.json").toPath(), profile.toPath()) }
            return
        }
        // No file — a fresh machine, or one where the plugin is the only thing that ever signed in. The
        // account we banked in the safe at sign-in is written back out, so the binary still knows who it is
        // and the dashboard names the account. The SAFE is the persistent memory for this; the file is a
        // disposable projection of it.
        AccountProfile.storedAccountJson()?.let { account ->
            runCatching {
                File(dir, ".claude.json").writeText("""{"oauthAccount":$account}""")
                restrict(File(dir, ".claude.json"), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
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
        runCatching { dir.deleteRecursively() }.onFailure { log.warn("could not remove the session config dir", it) }
    }

    private fun restrict(file: File, owner: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(file.toPath(), owner) }
            .onFailure { log.warn("could not restrict ${file.name}", it) }
    }

    private fun defaultHome(): File =
        CredentialsVault.homeOverride ?: File(System.getProperty("user.home").orEmpty())
}
