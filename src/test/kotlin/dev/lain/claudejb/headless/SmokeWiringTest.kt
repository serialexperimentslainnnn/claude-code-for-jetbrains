package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Smoke: confirms the headless test harness (IntelliJ Platform fixtures) boots in-process. */
class SmokeWiringTest : BasePlatformTestCase() {
    fun `test platform fixture boots`() {
        assertNotNull(project)
        assertNotNull(project.basePath)
    }
}
