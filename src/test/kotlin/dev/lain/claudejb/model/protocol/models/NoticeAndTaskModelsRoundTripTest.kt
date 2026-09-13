package dev.lain.claudejb.model.protocol.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoticeAndTaskModelsRoundTripTest {

    @Test
    fun `the notice models survive a round trip, full and empty`() {
        RoundTrip.check(NotificationInfo.serializer(), NotificationInfo("k", "t", "high", "red", 5000), NotificationInfo())
        RoundTrip.check(PermissionDeniedInfo.serializer(), PermissionDeniedInfo("Bash", "u", "a", "rule", "why", "m"), PermissionDeniedInfo())
        RoundTrip.check(MemoryRecallInfo.serializer(), MemoryRecallInfo("auto", listOf(RecalledMemory("p", "user", "c"))), MemoryRecallInfo())
        RoundTrip.check(RecalledMemory.serializer(), RecalledMemory("p", "s", "c"), RecalledMemory())
        RoundTrip.check(
            FilesPersistedInfo.serializer(),
            FilesPersistedInfo(listOf(PersistedFile("a", "1")), listOf(FailedFile("b", "e")), "now"),
            FilesPersistedInfo(),
        )
        RoundTrip.check(PersistedFile.serializer(), PersistedFile("a", "1"), PersistedFile())
        RoundTrip.check(FailedFile.serializer(), FailedFile("b", "e"), FailedFile())
        RoundTrip.check(PluginInstallInfo.serializer(), PluginInstallInfo("ok", "n", "e"), PluginInstallInfo())
        RoundTrip.check(MirrorErrorInfo.serializer(), MirrorErrorInfo("e", MirrorErrorKey("p", "s", "sub")), MirrorErrorInfo())
        RoundTrip.check(MirrorErrorKey.serializer(), MirrorErrorKey("p", "s", "sub"), MirrorErrorKey())
        RoundTrip.check(WorkerShuttingDownInfo.serializer(), WorkerShuttingDownInfo("bye"), WorkerShuttingDownInfo())
    }

    @Test
    fun `the refusal and informational notices survive a round trip`() {
        RoundTrip.check(
            ModelRefusalFallbackInfo.serializer(),
            ModelRefusalFallbackInfo("t", "d", "o", "f", "r", "cat", "exp", listOf("u1"), "c"),
            ModelRefusalFallbackInfo(),
        )
        RoundTrip.check(InformationalInfo.serializer(), InformationalInfo("c", "warn", "u", true), InformationalInfo())
        RoundTrip.check(
            ModelRefusalNoFallbackInfo.serializer(),
            ModelRefusalNoFallbackInfo("o", "r", "cat", "exp", "u", "c"),
            ModelRefusalNoFallbackInfo(),
        )
    }

    @Test
    fun `the task models survive a round trip`() {
        val usage = TaskUsage(10, 2, 300)
        RoundTrip.check(TaskUsage.serializer(), usage, TaskUsage())
        RoundTrip.check(TaskProgressInfo.serializer(), TaskProgressInfo("t", "u", "d", "Explore", usage, "Read", "s", "running", "e"), TaskProgressInfo())
        RoundTrip.check(TaskStartedInfo.serializer(), TaskStartedInfo("t", "u", "d", "Explore", "agent", "wf", "p", true), TaskStartedInfo())
        RoundTrip.check(TaskNotificationInfo.serializer(), TaskNotificationInfo("t", "u", "done", "/o", "s", usage, true), TaskNotificationInfo())
        RoundTrip.check(TaskUpdatedInfo.serializer(), TaskUpdatedInfo("t", TaskPatch("done", "d", 1, 2, "e", true)), TaskUpdatedInfo())
        RoundTrip.check(TaskPatch.serializer(), TaskPatch("done", "d", 1, 2, "e", false), TaskPatch())
        RoundTrip.check(ToolProgressInfo.serializer(), ToolProgressInfo("u", "Bash", "p", 1.5, "t"), ToolProgressInfo())
        RoundTrip.check(ToolUseSummaryInfo.serializer(), ToolUseSummaryInfo("s", listOf("u1")), ToolUseSummaryInfo())
        RoundTrip.check(BackgroundTaskInfo.serializer(), BackgroundTaskInfo("t", "shell", "d"), BackgroundTaskInfo())
        RoundTrip.check(BackgroundTasksChangedInfo.serializer(), BackgroundTasksChangedInfo(listOf(BackgroundTaskInfo("t"))), BackgroundTasksChangedInfo())
    }

    @Test
    fun `the usage models survive a round trip`() {
        RoundTrip.check(ContextUsage.serializer(), ContextUsage(10, 100, 10.0, listOf(ContextCategory("sys", 5))), ContextUsage())
        RoundTrip.check(ContextCategory.serializer(), ContextCategory("sys", 5), ContextCategory())
        RoundTrip.check(SessionCostUsage.serializer(), SessionCostUsage(1, 2, 3, 4), SessionCostUsage())
        RoundTrip.check(
            RateLimitInfo.serializer(),
            RateLimitInfo("rejected", 1, "five_hour", 0.5, "allowed", true, 2, true, 0.8),
            RateLimitInfo(),
        )
        RoundTrip.check(UsageWindow.serializer(), UsageWindow(50.0, "2026-09-13T00:00:00Z", 1.0, 0.5, 0.5, "Session"), UsageWindow())
        RoundTrip.check(ExtraUsage.serializer(), ExtraUsage(true, 10.0, 2.0, 0.2, "USD", 3, true, true), ExtraUsage())
    }

    @Test
    fun `a usage window titles itself, clamps its percent and knows when it has reset`() {
        assertEquals("Session", UsageWindow(displayName = "Session").title("five_hour"))
        assertEquals("Current session", UsageWindow(displayName = " ").title("five_hour"))
        assertEquals("All models", UsageWindow().title("seven_day"))
        assertEquals("Quota", UsageWindow().title(""))
        assertEquals("Haiku", UsageWindow().title("seven_day_haiku"))
        assertEquals(100, UsageWindow(utilization = 150.0).utilizationPercent())
        assertEquals(0, UsageWindow(utilization = -1.0).utilizationPercent())
        assertNull(UsageWindow().utilizationPercent())
        assertTrue(UsageWindow(resetsAt = "2026-09-13T00:00:00+02:00").hasReset(Long.MAX_VALUE))
        assertTrue(UsageWindow(resetsAt = "2026-09-13T00:00:00Z").hasReset(Long.MAX_VALUE))
        assertFalse(UsageWindow(resetsAt = "2026-09-13T00:00:00Z").hasReset(0))
        assertFalse(UsageWindow(resetsAt = "soon").hasReset(Long.MAX_VALUE))
        assertFalse(UsageWindow(resetsAt = " ").hasReset(Long.MAX_VALUE))
        assertFalse(UsageWindow().hasReset(Long.MAX_VALUE))
    }

    @Test
    fun `a rate limit names its window, reports its reset instant, and a report knows when it is empty`() {
        assertEquals("Opus", RateLimitInfo.windowTitleFor("seven_day_opus"))
        assertEquals("Sonnet", RateLimitInfo.windowTitleFor("seven_day_sonnet"))
        assertEquals("OAuth apps", RateLimitInfo.windowTitleFor("seven_day_oauth_apps"))
        assertEquals("Overage", RateLimitInfo.windowTitleFor("overage"))
        assertEquals("Quota", RateLimitInfo.windowTitleFor(null))
        assertEquals("Some thing", RateLimitInfo.windowTitleFor("seven_day_some_thing"))
        assertEquals("1970-01-01T00:00:10Z", RateLimitInfo(resetsAt = 10).resetsAtIso())
        assertNull(RateLimitInfo().resetsAtIso())
        assertTrue(UsageReport().isEmpty)
        assertFalse(UsageReport(extra = ExtraUsage()).isEmpty)
        assertFalse(UsageReport(windows = listOf("five_hour" to UsageWindow())).isEmpty)
    }
}
