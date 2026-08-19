package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SmokeWiringTest : BasePlatformTestCase() {
    fun `test platform fixture boots`() {
        assertNotNull(project)
        assertNotNull(project.basePath)
    }
}
