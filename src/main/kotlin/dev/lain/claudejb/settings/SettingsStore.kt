package dev.lain.claudejb.settings

import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.session.PluginAgentIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Where the plugin's settings live: **the IDE's PasswordSafe**, as one JSON document. There is no settings
 * file on disk.
 *
 * **Why not a file.** They started in `.idea/claude-code.xml` — per project, plaintext, committable — and
 * the reason for moving them was that the env block belongs to them, and an env block is where an API key,
 * a credentialed proxy URL or a registry token ends up. Moving that to
 * `~/.claude/ide/claude-code-native/settings.json` fixed the "committable" half and kept the plaintext, so
 * it was half a fix. The safe is the same store the OAuth credential and the API keys already use: the OS
 * keychain (Keychain, KWallet, DPAPI, or the IDE's encrypted file), application-wide, which is also exactly
 * the scope these settings have.
 *
 * A file that predates this is read once and deleted — see [load].
 *
 * The document is the compile-time-generated serialization of [ClaudeSettings.State] — the contract is the
 * class: an unknown key from a newer version is ignored, and a missing or damaged key falls back to the
 * property's default rather than to null.
 */
internal object SettingsStore {

    private val log = logger<SettingsStore>()
    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /**
     * Reads the settings from the safe, adopting a pre-existing settings FILE the first time.
     *
     * **A failed read is not an empty configuration.** If the safe cannot be reached (a locked KWallet, a
     * keychain that is not up yet) this returns defaults — but records that it failed, so [save] refuses to
     * write over what is in there. Without that, one bad read at startup became a permanent overwrite on the
     * next save: the settings "breaking" with nobody having touched them.
     */
    @Synchronized
    fun load(): ClaudeSettings.State {
        // NO STORE AT ALL is a third state, and it is neither of the two below.
        //
        // A test JVM that has not installed a [SecretStore.storeOverride] has nowhere to read from. That is
        // not "the safe failed" — there is no configuration behind it to protect, and [readFailed] is a flag
        // on an `object`, so setting it here would outlive this call and veto the saves of every later test
        // in the JVM: one inert read poisoning a suite, which is the same shape as the bug the flag exists to
        // prevent. Nor is it silently "read fine, found nothing": [save] refuses on the same condition, so
        // nothing can round-trip through a store that is not there and quietly appear to work.
        if (SecretStore.inert()) {
            readFailed = false
            return ClaudeSettings.State()
        }
        val stored = runCatching { SecretStore.get(SecretStore.SETTINGS_JSON) }
        readFailed = stored.isFailure
        stored.onFailure { log.warn("could not read the settings from the password safe", it) }
        stored.getOrNull()?.let { body ->
            val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
            if (obj != null) return decode(obj)
            log.warn("the stored settings are not readable JSON; using defaults")
            readFailed = true // do not let the next save consolidate defaults over something unreadable
            return ClaudeSettings.State()
        }
        if (stored.isFailure) return ClaudeSettings.State()
        return adoptFile()
    }

    /**
     * Adopts `~/.claude/ide/claude-code-native/settings.json` — written by 5.5.0 before the settings moved
     * into the safe — and removes it.
     *
     * Removed rather than left behind: it is plaintext, it holds what the user configured, and leaving a
     * stale copy of a configuration around is how the next version ends up reading the wrong one.
     */
    private fun adoptFile(): ClaudeSettings.State {
        val file = file() ?: return ClaudeSettings.State()
        val body = runCatching { Files.readString(file) }.getOrNull()
        if (body.isNullOrBlank()) return ClaudeSettings.State()
        val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ClaudeSettings.State().also { keepUnreadable(file) }
        val state = decode(obj)
        // The env block was already in the safe under its own name; keep it, and fold it into the document.
        state.envVars = runCatching { SecretStore.get(SecretStore.ENV_VARS) }.getOrNull().orEmpty()
        // DELETE ONLY ON A CONFIRMED SAVE. This exact sequence lost a configuration on this machine: the
        // file was adopted and removed, and the safe's write failed a millisecond later
        // (`secret_password_store_sync error code 36 — Can't find session …`), which `set` reports to
        // nobody. The file was the only copy. Now the copy has to be readable back before the original goes.
        if (!save(state)) {
            log.warn("keeping $file: the password safe did not accept the settings")
            return state
        }
        runCatching { Files.delete(file) }
            .onSuccess { log.info("adopted the settings from $file into the password safe and removed the file") }
            .onFailure { log.warn("could not remove the migrated settings file $file", it) }
        return state
    }

    /**
     * Whether the last read of the settings FAILED, as opposed to finding nothing.
     *
     * The distinction is load-bearing: [save] must not write defaults over a configuration it simply could
     * not read this run.
     *
     * It is one flag for the whole object, which is why every entry point that reads or writes the document
     * ([load], [save], [mutate], [migrateFrom]) holds this object's monitor: a read on one thread and a write
     * on another would otherwise interleave into a save that consults somebody else's verdict.
     */
    @Volatile
    private var readFailed = false

    /**
     * Moves an unparseable settings file aside instead of letting the next [save] overwrite it.
     *
     * Falling back to defaults is the only thing a reader can do, but the file it could not read is the
     * user's whole configuration — and the very next save would write defaults over it, turning a bad read
     * into permanent loss. Kept as `settings.json.unreadable`, which costs nothing and leaves the evidence
     * on disk. Writes are atomic now (see [save]), so this should never trigger; it is the net under it.
     */
    private fun keepUnreadable(file: Path) {
        log.warn("settings file is not readable JSON; using defaults and keeping it as ${file.fileName}.unreadable")
        runCatching {
            Files.move(file, file.resolveSibling(file.fileName.toString() + ".unreadable"), REPLACE_EXISTING)
        }.onFailure { log.warn("could not set the unreadable settings file aside", it) }
    }

    /**
     * Writes the whole configuration into the OS credential store, as one document.
     *
     * ONE destination, and no file: the env block travels inside the document, so an API key or a
     * credentialed proxy URL typed into Settings is encrypted at rest exactly like the OAuth credential
     * beside it — the same [SecretStore], the same PasswordSafe, the same keychain.
     *
     * Refuses to write when this run could not READ the settings ([readFailed]). Saving then would replace a
     * configuration we never saw with the defaults we fell back to, which is a data loss caused entirely by
     * a transient safe.
     */
    @Synchronized
    fun save(state: ClaudeSettings.State): Boolean {
        // Nowhere to write (a test JVM with no store of its own): refuse and say so, rather than report a
        // success nothing kept. Ahead of the [SafeAlarm] path deliberately — there is no failing OS store
        // here for the user to go and fix, so raising that alarm would be a false one.
        if (SecretStore.inert()) {
            log.debug("not saving the settings: no credential store is installed in this JVM")
            return false
        }
        if (readFailed) {
            log.warn("not saving the settings: they could not be read this run, and defaults must not replace them")
            return false
        }
        val document = JSON.encodeToString(JsonObject.serializer(), encode(state))
        val stored = SecretStore.setVerified(SecretStore.SETTINGS_JSON, document)
        if (!stored) {
            // LOUD, not a log line. The IDE's own store failing is invisible from the outside — the settings
            // simply do not come back next time — and the user is the only one who can fix it (unlock the
            // keyring, or point Settings ▸ Appearance & Behavior ▸ System Settings ▸ Passwords elsewhere).
            SafeAlarm.storeFailed()
            return false
        }
        // The env block used to be its own entry. It rides inside the document now, so the old one is
        // dropped — but only once the document is verifiably in the safe.
        runCatching { SecretStore.clear(SecretStore.ENV_VARS) }
        return true
    }

    /**
     * Applies [delta] to what is STORED — read, change, write — and reports whether it reached the safe.
     *
     * **No write is derived from an in-memory copy, and that is the whole rule.** The safe is
     * application-wide, so two IDEs open on the same machine share one document; a save built from a copy
     * taken minutes ago carries that copy's value for every field the other IDE has changed since, and
     * replaces them. Re-reading first narrows what a save can overwrite to the fields [delta] actually
     * touches. It also makes the platform's own flush at shutdown harmless: what that writes back is
     * whatever was last handed to the safe, and what is handed to the safe here came from a fresh read.
     *
     * Within one process the monitor is what stops two mutations reading the same document and writing it
     * back one after the other.
     *
     * **A failed read aborts the write.** [load] falls back to defaults when the safe cannot be reached, and
     * applying a delta to those defaults produces a complete, plausible document that is not the user's
     * configuration — the one thing that must never reach the safe. [save] refuses on the same condition;
     * refusing here as well means nothing half-reconstructed is ever built, let alone written. A dropped
     * mutation is recoverable by making it again; a wiped configuration is not.
     */
    @Synchronized
    fun mutate(delta: (ClaudeSettings.State) -> Unit): Boolean {
        val stored = load()
        if (readFailed) {
            log.warn("not applying the settings change: the stored settings could not be read this run")
            return false
        }
        delta(stored)
        return save(stored)
    }

    /**
     * The stored settings, or null when the safe could not be read.
     *
     * [load]'s contract is "defaults when the safe cannot be reached", which is right for the first read of a
     * session — the plugin has to start with something. It is wrong for a REFRESH of an in-memory copy that is
     * already good: replacing a real configuration with defaults because a keyring was locked for a moment
     * shows the user an empty settings page, and the page is a door onto a save. Null is the caller's signal
     * to keep what it already has.
     */
    @Synchronized
    fun loadOrNull(): ClaudeSettings.State? = load().takeUnless { readFailed }

    /**
     * Adopts a legacy per-project state and writes it here, once.
     *
     * Returns true when it actually migrated, which is the caller's signal to remove the old file — the
     * order matters: nothing is deleted until the new location holds the data.
     *
     * **Two things it refuses to do, and both were real ways to lose a configuration.**
     *
     * It never touches an existing file: once these settings live here, THIS is the configuration, and a
     * project's leftover XML is history, not an input.
     *
     * And it never creates the file out of a legacy state that carries nothing. The legacy component is
     * declared with the old name and storage, so the platform hands us `claude-code.xml` when the project
     * has one — and a state of pure defaults when it does not. Writing that was indistinguishable from a
     * real migration: the first project opened after a reinstall could CREATE the global file full of
     * factory values and mark the migration done, so the actual settings (in another project's XML, opened
     * later) were never adopted and the user saw a plugin reset to defaults. Nothing to migrate is now
     * exactly that — nothing — and the next project that does carry an XML still gets its turn.
     *
     * **And one thing it refuses to ADOPT**: a permission mode weaker than the default — see
     * [LegacyPermissionMode] and [withoutWeakenedSecurity]. That check runs first, so a file whose only
     * content was such a mode counts as carrying nothing and does not create the document either.
     */
    @Synchronized
    fun migrateFrom(legacy: ClaudeSettings.State): Boolean {
        if (exists()) return false // already migrated, or already configured here
        val adoptable = withoutWeakenedSecurity(legacy)
        if (encode(adoptable) == encode(ClaudeSettings.State())) {
            log.info("no legacy settings to migrate (the project carries none)")
            return false
        }
        save(adoptable)
        log.info("migrated plugin settings from the project's claude-code.xml into the password safe")
        return exists()
    }

    /**
     * The legacy state as it may be adopted: everything it carries, minus a permission mode a repository has
     * no business choosing for every project the user will ever open ([LegacyPermissionMode.weakensSecurity]).
     *
     * Returns a COPY when it changes something, never the caller's object: the state it is handed belongs to
     * [LegacyProjectSettings], i.e. to a live `PersistentStateComponent` whose file is still on disk at this
     * point. Editing that in place would mark the component dirty and invite the platform to write
     * `claude-code.xml` back out — resurrecting, with our own contents, the file this migration exists to
     * remove.
     */
    private fun withoutWeakenedSecurity(legacy: ClaudeSettings.State): ClaudeSettings.State {
        if (!LegacyPermissionMode.weakensSecurity(legacy.permissionMode)) return legacy
        LegacySettingsNotice.permissionModeRefused(legacy.permissionMode)
        return copyOf(legacy).apply { permissionMode = LegacyPermissionMode.SAFE }
    }

    /** A detached copy, through the document's own serializer — the one place that already knows every field. */
    private fun copyOf(state: ClaudeSettings.State): ClaudeSettings.State =
        JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), encode(state))

    /** Whether a configuration has ever been stored. */
    fun exists(): Boolean = runCatching { SecretStore.get(SecretStore.SETTINGS_JSON) != null }.getOrDefault(false)

    /**
     * The settings FILE 5.5.0 used to write, for [adoptFile] to read once and delete. Nothing writes here.
     *
     * `homeDir()`, never `homeOverride`: in a test JVM that has not named a directory of its own it returns
     * null. That is the fix for the worst bug of this release — the tests were reading and writing the
     * developer's real configuration, which is how `haiku` and `some-unlisted-model`, both of them test
     * fixtures, ended up in a live install.
     */
    private fun file(): Path? = PluginAgentIndex.homeDir()?.let { Paths.get(it) }
        ?.resolve("ide")?.resolve("claude-code-native")?.resolve("settings.json")

    // The document IS the @Serializable State, field for field. `encodeDefaults` keeps every key present
    // (the document is the contract); `ignoreUnknownKeys`/`coerceInputValues` make a newer or damaged key
    // fall back to the property's default instead of breaking the read. The env block rides INSIDE the
    // document: it used to be excluded because the document was a plaintext file — moot now that the
    // document itself lives in the OS credential store.
    private fun encode(s: ClaudeSettings.State): JsonObject =
        JSON.encodeToJsonElement(ClaudeSettings.State.serializer(), s).jsonObject

    private fun decode(o: JsonObject): ClaudeSettings.State =
        runCatching { JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), o) }
            .getOrElse {
                log.warn("stored settings did not decode; using defaults", it)
                readFailed = true // do not let the next save consolidate defaults over this
                ClaudeSettings.State()
            }
}
