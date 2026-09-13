package dev.lain.claudejb.controller.mcp

import dev.lain.claudejb.model.mcp.OwnTools
import dev.lain.claudejb.model.mcp.ToolGate
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.model.permission.SensitiveGuard
import kotlinx.serialization.json.JsonObject

internal class GuardGate(private val decide: (JsonObject) -> SensitiveGuard.Decision) : ToolGate {

    override fun denial(tool: ToolSpec, arguments: JsonObject): String? {
        val decision = decide(OwnTools.guardInput(arguments))
        if (decision.verdict == SensitiveGuard.Verdict.ALLOW) return null
        return decision.reason ?: "refused by the Sensitive Guard"
    }
}
