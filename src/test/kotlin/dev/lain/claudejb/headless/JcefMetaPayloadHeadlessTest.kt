package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.jcef.JcefState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JcefMetaPayloadHeadlessTest : BasePlatformTestCase() {

    fun `test every install method carries the fields the boot card renders`() {
        val meta = Json.parseToJsonElement(JcefState.metaJson(ClaudeSession(project, "Chat"))).jsonObject
        val methods = meta["installMethods"]!!.jsonArray
        assertTrue("this OS must offer at least one install route", methods.isNotEmpty())
        methods.forEach { element ->
            val m = element.jsonObject
            listOf("id", "label", "display", "shell").forEach { key ->
                val value = m[key]?.jsonPrimitive?.content
                assertNotNull("installMethods[].$key is missing: $m", value)
                assertTrue("installMethods[].$key is blank: $m", value!!.isNotBlank())
            }
        }
    }
}
