package dev.lain.claudejb.drift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Offline unit tests for the pure drift extraction/diff logic — fed hand-written `.d.ts` and NDJSON
 * fixtures so they're deterministic and need no network or binary. The live download + probe is exercised
 * separately by [DriftLiveCheck] (tagged `driftLive`, run only by `./gradlew checkDrift`).
 */
class DriftDetectorTest {

    // -- ProtocolSurface.fromDts ------------------------------------------------------------------

    @Test
    fun `fromDts extracts subtype literals`() {
        val dts = """
            export type Foo = { subtype: 'can_use_tool'; };
            export type Bar = { subtype: 'get_session_cost'; };
            interface Baz { memory_type: 'User' | 'Project'; }
        """.trimIndent()
        val s = ProtocolSurface.fromDts(dts)
        assertEquals(setOf("can_use_tool", "get_session_cost"), s.subtypes)
        // memory_type is not a subtype literal — must not be captured.
        assertFalse("User" in s.subtypes)
    }

    @Test
    fun `fromDts extracts union members and strips qualifier`() {
        val dts = """
            export declare type SDKMessage = SDKAssistantMessage | SDKUserMessage | SDKResultMessage;
            declare type StdoutMessage = coreTypes.SDKMessage | SDKControlResponse | SDKKeepAliveMessage;
        """.trimIndent()
        val s = ProtocolSurface.fromDts(dts)
        assertTrue("SDKAssistantMessage" in s.unionMembers)
        assertTrue("SDKResultMessage" in s.unionMembers)
        // coreTypes.SDKMessage -> SDKMessage (qualifier dropped)
        assertTrue("SDKMessage" in s.unionMembers)
        assertTrue("SDKControlResponse" in s.unionMembers)
    }

    @Test
    fun `fromDts tolerates double quotes`() {
        val s = ProtocolSurface.fromDts("""x: { subtype: "set_model"; }""")
        assertTrue("set_model" in s.subtypes)
    }

    // -- ProtocolSurface.fromCapture --------------------------------------------------------------

    @Test
    fun `fromCapture reads top-level type and nested control subtype`() {
        val ndjson = """
            {"type":"system","subtype":"init","session_id":"abc"}
            {"type":"assistant","message":{"role":"assistant"}}
            {"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Read"}}
            {"type":"keep_alive"}
            not json at all
            {"type":"result","subtype":"success"}
        """.trimIndent()
        val s = ProtocolSurface.fromCapture(ndjson)
        assertEquals(setOf("system", "assistant", "control_request", "keep_alive", "result"), s.eventTypes)
        assertTrue("init" in s.subtypes)
        assertTrue("can_use_tool" in s.subtypes)   // pulled from the nested request object
        assertTrue("success" in s.subtypes)
    }

    @Test
    fun `fromCapture skips malformed lines without throwing`() {
        val s = ProtocolSurface.fromCapture("garbage\n{\"type\":\"system\"}\n{bad json")
        assertEquals(setOf("system"), s.eventTypes)
    }

    // -- DriftDetector.sdkDrift (latest .d.ts vs the plugin's KNOWN_SUBTYPES) ----------------------

    @Test
    fun `sdkDrift flags a subtype the parser does not model`() {
        // A .d.ts that declares a known subtype plus a brand-new one the parser doesn't handle.
        val latest = """
            type A = { subtype: 'can_use_tool'; };
            type B = { subtype: 'brand_new_kind'; };
        """.trimIndent()
        val drift = DriftDetector.sdkDrift(latest)
        assertTrue(drift.hasDrift)
        assertEquals(setOf("brand_new_kind"), drift.unmodeledSubtypes)
        assertFalse("can_use_tool" in drift.unmodeledSubtypes) // already modeled
    }

    @Test
    fun `sdkDrift is clean when every declared subtype is already modeled`() {
        // Declare only subtypes that are in KNOWN_SUBTYPES → no unmodeled drift.
        val latest = ProtocolSurface.KNOWN_SUBTYPES.joinToString("\n") { "x: { subtype: '$it'; };" }
        assertFalse(DriftDetector.sdkDrift(latest).hasDrift)
    }

    @Test
    fun `sdkDrift reports a modeled subtype the SDK dropped as informational, not actionable`() {
        // Latest declares nothing → every KNOWN subtype is "stale", but that's informational (not drift).
        val drift = DriftDetector.sdkDrift("// no subtypes here")
        assertFalse(drift.hasDrift)
        assertTrue(drift.staleSubtypes.containsAll(ProtocolSurface.KNOWN_SUBTYPES))
    }

    // -- DriftDetector.binaryDrift ----------------------------------------------------------------

    @Test
    fun `binaryDrift flags an unknown top-level type as hard drift`() {
        val ndjson = """
            {"type":"system","subtype":"init"}
            {"type":"some_brand_new_event","payload":1}
        """.trimIndent()
        val drift = DriftDetector.binaryDrift(ndjson)
        assertTrue(drift.hasHardDrift)
        assertEquals(setOf("some_brand_new_event"), drift.unknownEventTypes)
    }

    @Test
    fun `binaryDrift is clean when only known types are emitted`() {
        val ndjson = """
            {"type":"system","subtype":"init"}
            {"type":"assistant"}
            {"type":"result","subtype":"success"}
        """.trimIndent()
        val drift = DriftDetector.binaryDrift(ndjson)
        assertFalse(drift.hasHardDrift)
        assertTrue(drift.unmodeledSubtypes.isEmpty())
    }

    @Test
    fun `binaryDrift surfaces an unmodeled subtype as soft drift`() {
        val ndjson = """{"type":"system","subtype":"some_future_system_subtype"}"""
        val drift = DriftDetector.binaryDrift(ndjson)
        assertFalse(drift.hasHardDrift) // type 'system' is known
        assertEquals(setOf("some_future_system_subtype"), drift.unmodeledSubtypes)
    }

    // -- DriftReport ------------------------------------------------------------------------------

    @Test
    fun `report is non-actionable on a version bump whose surface stays fully covered`() {
        // Latest declares only modeled subtypes → not actionable; versions advanced → "bump baseline".
        val coveredDts = ProtocolSurface.KNOWN_SUBTYPES.joinToString("\n") { "x: { subtype: '$it'; };" }
        val report = DriftReport(
            sdkBaselineVersion = "0.3.161", sdkLatestVersion = "0.3.162",
            binaryBaselineVersion = "2.1.161", binaryInstalledVersion = "2.1.162",
            sdk = DriftDetector.sdkDrift(coveredDts),
            binary = DriftDetector.binaryDrift("""{"type":"system","subtype":"init"}"""),
        )
        assertFalse(report.actionable)
        assertTrue(report.sdkVersionChanged)
        assertTrue(report.render().contains("bump the recorded baseline"))
    }

    @Test
    fun `report is fully clean when nothing changed`() {
        val coveredDts = ProtocolSurface.KNOWN_SUBTYPES.joinToString("\n") { "x: { subtype: '$it'; };" }
        val report = DriftReport(
            sdkBaselineVersion = "0.3.162", sdkLatestVersion = "0.3.162",
            binaryBaselineVersion = "2.1.162", binaryInstalledVersion = "2.1.162",
            sdk = DriftDetector.sdkDrift(coveredDts),
            binary = DriftDetector.binaryDrift("""{"type":"system","subtype":"init"}"""),
        )
        assertFalse(report.actionable)
        assertTrue(report.render().contains("No drift"))
    }

    @Test
    fun `report is actionable and names the file when an unmodeled subtype appears`() {
        val report = DriftReport(
            sdkBaselineVersion = "0.3.161", sdkLatestVersion = "0.3.162",
            binaryBaselineVersion = "2.1.162", binaryInstalledVersion = "2.1.162",
            sdk = DriftDetector.sdkDrift("type B = { subtype: 'totally_new_subtype'; };"),
            binary = DriftDetector.binaryDrift(""),
        )
        assertTrue(report.actionable)
        val md = report.render()
        assertTrue(md.contains("Drift detected"))
        assertTrue(md.contains("`totally_new_subtype`"))
        assertTrue(md.contains("ClaudeEvent.kt"))
    }
}
