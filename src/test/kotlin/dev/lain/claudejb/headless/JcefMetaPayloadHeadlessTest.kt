package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.jcef.JcefState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `cc.meta` payload carries every key the boot card actually reads.
 *
 * This pins a defect that was invisible precisely because the frontend was defensive about it: the
 * "Claude Code was not found" card renders its copy hint as `'or copy this command to ' + (m.shell ||
 * 'a shell')`, and the payload never emitted `shell` — so every install route on every OS read "a shell"
 * instead of "bash" / "PowerShell" / "cmd". `BinaryInstall.Method.shell` was populated all along and the
 * card is its only consumer, which is exactly why it looked like dead data rather than a dropped key.
 *
 * The assertion is the CONTRACT, not the wording: each method must carry the four fields the card reads
 * (`id`, `label`, `display`, `shell`), all non-blank. The routes themselves are OS-dependent, so nothing
 * here asserts which ones are offered.
 */
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
