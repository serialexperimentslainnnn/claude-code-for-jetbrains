package dev.lain.claudejb.drift

object DriftDetector {

    fun sdkDrift(latestDts: String): SdkDrift {
        val latest = ProtocolSurface.fromDts(latestDts)
        return SdkDrift(
            unmodeledSubtypes = latest.subtypes - ProtocolSurface.KNOWN_SUBTYPES,
            staleSubtypes = ProtocolSurface.KNOWN_SUBTYPES - latest.subtypes,
        )
    }

    fun binaryDrift(capture: String): BinaryDrift {
        val seen = ProtocolSurface.fromCapture(capture)
        return BinaryDrift(
            unknownEventTypes = seen.eventTypes - ProtocolSurface.KNOWN_EVENT_TYPES,
            unmodeledSubtypes = seen.subtypes - ProtocolSurface.KNOWN_SUBTYPES,
        )
    }
}

data class SdkDrift(
    val unmodeledSubtypes: Set<String>,
    val staleSubtypes: Set<String>,
) {
    val hasDrift: Boolean get() = unmodeledSubtypes.isNotEmpty()
}

data class BinaryDrift(
    val unknownEventTypes: Set<String>,
    val unmodeledSubtypes: Set<String>,
) {
    val hasHardDrift: Boolean get() = unknownEventTypes.isNotEmpty()
}

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
                if (sdkVersionChanged) {
                    appendLine(
                        "- `package.json` / `node_modules` SDK → `$sdkLatestVersion` (done by `npm update`); `KNOWN_*` unchanged.",
                    )
                }
                if (binaryVersionChanged) appendLine("- `scripts/drift-baseline.properties` `binary` → `$binaryInstalledVersion`.")
            } else {
                appendLine("✅ **No drift.** Versions and protocol surface are unchanged.")
            }
            return@buildString
        }

        appendLine("⚠️ **Drift detected — protocol code changes needed.**")
        appendLine()
        section(
            "SDK — `subtype`s not modeled by the parser",
            sdk.unmodeledSubtypes,
            "→ add a typed branch + serializer in `protocol/ClaudeEvent.kt` (system subtype) or " +
                "`protocol/ControlProtocol.kt` (control kind), then add it to `KNOWN_SUBTYPES`.",
        )
        section(
            "Binary — UNKNOWN top-level `type`s (hard: bucketed as Other)",
            binary.unknownEventTypes,
            "→ add a `when (type)` branch in `protocol/ClaudeEvent.kt`, then add it to `KNOWN_EVENT_TYPES`.",
        )
        section(
            "Binary — runtime `subtype`s not yet typed (soft)",
            binary.unmodeledSubtypes,
            "→ absorbed as `Other`/`UnsupportedControlRequest` today; model + add to `KNOWN_SUBTYPES` if useful.",
        )
        section(
            "SDK — `subtype`s the parser models but the SDK dropped (informational)",
            sdk.staleSubtypes,
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
