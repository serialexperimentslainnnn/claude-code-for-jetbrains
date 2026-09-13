package dev.lain.claudejb.model.mcp

import kotlinx.serialization.json.JsonObject

class RunCard(val title: String, val summary: String) {

    companion object {

        fun of(toolName: String, input: JsonObject): RunCard? {
            val call = OwnTools.parse(toolName, input) ?: return null
            val what = call.argument ?: "?"
            return when (call.meta) {
                MetaTools.RUN.name -> {
                    RunCard("Claude wants to use $what on the ${call.server} server", OwnTools.argsToon(OwnTools.argsOf(input)).orEmpty())
                }

                MetaTools.TOOLS.name -> RunCard("Claude asks the ${call.server} server for its $what tools", "")

                else -> RunCard("Claude asks the ${call.server} server for its domains", "")
            }
        }
    }
}
