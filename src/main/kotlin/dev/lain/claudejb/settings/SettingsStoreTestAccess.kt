package dev.lain.claudejb.settings

import org.jetbrains.annotations.TestOnly

/**
 * Test-only door onto [SettingsStore], which is `internal` to this package.
 *
 * The store is deliberately not public: everything in production goes through [ClaudeSettings], so that the
 * settings have one owner and one place that decides when they are written. The tests, however, need to
 * exercise the store itself — the safe round-trip, the field coverage, the migration rules — without a
 * project service in the way.
 */
@TestOnly
object SettingsStoreTestAccess {
    fun load(): ClaudeSettings.State = SettingsStore.load()
    fun save(state: ClaudeSettings.State) = SettingsStore.save(state)
    fun migrateFrom(legacy: ClaudeSettings.State): Boolean = SettingsStore.migrateFrom(legacy)
}
