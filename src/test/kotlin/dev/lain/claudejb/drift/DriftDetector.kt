package dev.lain.claudejb.drift

/**
 * Computes protocol drift after the live tools have been brought up to date, and renders an
 * **agent-consumable** report. The model is "update to latest, then ask whether the plugin's code still
 * covers the protocol" — so both axes diff the *latest* surface against what the plugin already models
 * ([ProtocolSurface.KNOWN_SUBTYPES] / [ProtocolSurface.KNOWN_EVENT_TYPES], mirrored from
 * `protocol/ClaudeEvent.kt`), not against a previous snapshot.
 *
 *  - SDK drift: subtypes the latest `sdk.d.ts` declares that the parser doesn't type yet.
 *  - Binary drift: top-level `type`s the auto-updated binary emits that hit the parser's `else -> Other`
 *    branch, plus runtime `subtype`s not yet modeled.
 *
 * The report names the `protocol/` file each gap touches, so reconciliation is mechanical: add the
 * serializer/branch, extend the KNOWN_* set, bump the baseline version, re-run to confirm green.
 */
object DriftDetector {

    /** Diff the latest SDK declaration surface against what the plugin models. */
    fun sdkDrift(latestDts: String): SdkDrift {
        val latest = ProtocolSurface.fromDts(latestDts)
        return SdkDrift(
            unmodeledSubtypes = latest.subtypes - ProtocolSurface.KNOWN_SUBTYPES,
            staleSubtypes = ProtocolSurface.KNOWN_SUBTYPES - latest.subtypes,
        )
    }

    /** Diff a live binary capture against what the plugin models. */
    fun binaryDrift(capture: String): BinaryDrift {
        val seen = ProtocolSurface.fromCapture(capture)
        return BinaryDrift(
            unknownEventTypes = seen.eventTypes - ProtocolSurface.KNOWN_EVENT_TYPES,
            unmodeledSubtypes = seen.subtypes - ProtocolSurface.KNOWN_SUBTYPES,
        )
    }
}

data class SdkDrift(
    /** Latest SDK declares these `subtype`s but the parser doesn't model them — actionable. */
    val unmodeledSubtypes: Set<String>,
    /** Parser models these but the latest SDK no longer declares them — informational (possible cleanup). */
    val staleSubtypes: Set<String>,
) {
    val hasDrift: Boolean get() = unmodeledSubtypes.isNotEmpty()
}

data class BinaryDrift(
    val unknownEventTypes: Set<String>,
    val unmodeledSubtypes: Set<String>,
) {
    /** An unknown *top-level* type is hard drift (silently bucketed as `Other`); an unmodeled subtype is
     *  soft (already absorbed, but a candidate for a typed model). */
    val hasHardDrift: Boolean get() = unknownEventTypes.isNotEmpty()
}

/**
 * Full report across both axes plus the version deltas. [actionable] is true when a *surface* gap exists
 * (an SDK/binary kind the plugin doesn't model) — a bare version bump with a fully-covered surface is NOT
 * actionable (no false alarm on a patch release; you just bump the recorded baseline).
 */
data class DriftReport(
    val sdkBaselineVersion: String,
    val sdkLatestVersion: String,
    val binaryBaselineVersion: String,
    val binaryInstalledVersion: String,
    val sdk: SdkDrift,
    val binary: BinaryDrift,
) {
    val actionable: Boolean
        get() = sdk.hasDrift || binary.hasHardDrift || binary.unmodeledSubtypes.isNotEmpty()

    val sdkVersionChanged: Boolean get() = sdkBaselineVersion != sdkLatestVersion
    val binaryVersionChanged: Boolean get() = binaryBaselineVersion != binaryInstalledVersion

    fun render(): String = buildString {
        appendLine("# Protocol drift report")
        appendLine()
        appendLine("| Source | Baseline | Latest (updated) |")
        appendLine("|---|---|---|")
        appendLine("| SDK (`@anthropic-ai/claude-agent-sdk`) | `$sdkBaselineVersion` | `$sdkLatestVersion` |")
        appendLine("| `claude` binary | `$binaryBaselineVersion` | `$binaryInstalledVersion` |")
        appendLine()

        if (!actionable) {
            if (sdkVersionChanged || binaryVersionChanged) {
                appendLine("✅ **Versions advanced, but the protocol surface is fully covered.**")
                appendLine()
                appendLine("Action: bump the recorded baseline only —")
                if (sdkVersionChanged) appendLine("- `package.json` / `node_modules` SDK → `$sdkLatestVersion` (done by `npm update`); `KNOWN_*` unchanged.")
                if (binaryVersionChanged) appendLine("- `scripts/drift-baseline.properties` `binary` → `$binaryInstalledVersion`.")
            } else {
                appendLine("✅ **No drift.** Versions and protocol surface are unchanged.")
            }
            return@buildString
        }

        appendLine("⚠️ **Drift detected — protocol code changes needed.**")
        appendLine()
        section(
            "SDK — `subtype`s not modeled by the parser", sdk.unmodeledSubtypes,
            "→ add a typed branch + serializer in `protocol/ClaudeEvent.kt` (system subtype) or " +
                "`protocol/ControlProtocol.kt` (control kind), then add it to `KNOWN_SUBTYPES`.",
        )
        section(
            "Binary — UNKNOWN top-level `type`s (hard: bucketed as Other)", binary.unknownEventTypes,
            "→ add a `when (type)` branch in `protocol/ClaudeEvent.kt`, then add it to `KNOWN_EVENT_TYPES`.",
        )
        section(
            "Binary — runtime `subtype`s not yet typed (soft)", binary.unmodeledSubtypes,
            "→ absorbed as `Other`/`UnsupportedControlRequest` today; model + add to `KNOWN_SUBTYPES` if useful.",
        )
        section(
            "SDK — `subtype`s the parser models but the SDK dropped (informational)", sdk.staleSubtypes,
            "→ verify we don't still send/expect these; prune `KNOWN_SUBTYPES` if truly gone.",
        )
        appendLine("Then bump the baseline versions and re-run `./gradlew checkDrift` to confirm green.")
    }

    private fun StringBuilder.section(title: String, items: Set<String>, hint: String) {
        if (items.isEmpty()) return
        appendLine("## $title")
        items.sorted().forEach { appendLine("- `$it`") }
        appendLine(hint)
        appendLine()
    }
}
