package dev.lain.claudejb.model.mcp

import dev.lain.claudejb.model.mcp.toon.Toon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun interface ToolGate {
    fun denial(tool: ToolSpec, arguments: JsonObject): String?
}

class MetaTools(private val catalog: ToolCatalog, private val gate: ToolGate, private val budget: OutputBudget) {

    val specs: List<ToolSpec> = listOf(DOMAINS, TOOLS, RUN)

    suspend fun call(name: String, arguments: JsonObject, meta: JsonObject = JsonObject(emptyMap())): ToolResult? = when (name) {
        DOMAINS.name -> domains()
        TOOLS.name -> tools(ToolArgs(arguments))
        RUN.name -> run(arguments, meta)
        else -> null
    }

    private fun domains(): ToolResult {
        val table = buildJsonObject {
            put(
                "domains",
                buildJsonArray {
                    for (domain in catalog.domains) {
                        add(
                            buildJsonObject {
                                put("name", domain.name)
                                put("description", domain.description)
                            },
                        )
                    }
                },
            )
        }
        return ToolResult(PRIMER + Toon.encode(table))
    }

    private fun tools(args: ToolArgs): ToolResult {
        val name = args.string("domain")
        val domain = catalog.domain(name)
            ?: return ToolResult.error("unknown domain $name; the domains are ${catalog.domains.joinToString { it.name }}")
        val listing = buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    for (tool in domain.tools) add(describe(tool.spec))
                },
            )
        }
        return ToolResult(Toon.encode(listing))
    }

    private fun describe(spec: ToolSpec): JsonObject = buildJsonObject {
        put("name", spec.name)
        put("description", spec.description)
        if (spec.mutates) put("mutates", true)
        put(
            "params",
            buildJsonArray {
                for (param in spec.params) {
                    add(
                        buildJsonObject {
                            put("name", param.name)
                            put("type", param.type)
                            put("required", param.required)
                            put("description", param.description)
                        },
                    )
                }
            },
        )
    }

    private suspend fun run(arguments: JsonObject, meta: JsonObject): ToolResult {
        val name = ToolArgs(arguments).string("tool")
        val tool = catalog.tool(name) ?: return ToolResult.error("unknown tool $name; call domains() then tools(domain)")
        gate.denial(tool.spec, arguments)?.let { return ToolResult.error(it) }
        val args = arguments["args"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
        val toolUseId = (meta[OwnTools.TOOL_USE_ID_KEY] as? JsonPrimitive)?.content
        val result = runCatching { withTimeout(tool.spec.timeoutMillis) { tool.run(ToolArgs(args, toolUseId)) } }
            .getOrElse { failure(name, tool.spec.timeoutMillis, it) }
        return ToolResult(budget.fit(result.text), result.isError)
    }

    private fun failure(name: String, timeoutMillis: Long, cause: Throwable): ToolResult = when (cause) {
        is ToolException -> ToolResult.error(cause.message ?: "tool failed")
        is TimeoutCancellationException -> ToolResult.error(overrun(name, timeoutMillis, cause))
        is CancellationException -> throw cause
        is LinkageError -> ToolResult.error("$name needs an API this IDE build does not have: ${cause.message}")
        else -> ToolResult.error("$name failed: ${cause::class.simpleName}: ${cause.message}")
    }

    private fun overrun(name: String, timeoutMillis: Long, cause: TimeoutCancellationException): String =
        "$name did not finish within ${timeoutMillis / MILLIS} s (${cause.message}); narrow the request, or cancel and retry"

    companion object {

        val DOMAINS = ToolSpec("domains", "Lists this server's tool domains, one line each. Start here.")

        val TOOLS = ToolSpec(
            "tools",
            "Lists the tools of one domain with their parameters. Call it only for the domain you are about to use.",
            listOf(Param("domain", "A domain name from domains()")),
        )

        val RUN = ToolSpec(
            "run",
            "Runs one tool from tools(domain) with its arguments. Results are TOON.",
            listOf(
                Param("tool", "The tool name exactly as listed by tools(domain)"),
                Param("args", "The tool's arguments as an object", type = "object", required = false),
            ),
            mutates = true,
        )

        private const val MILLIS = 1000L

        const val PRIMER =
            "# TOON: key: value | key[N]: a,b | key[N]{f1,f2}: then one row per line | quotes only when needed\n" +
                "# Next: tools(domain) for one domain's tools, then run(tool, args).\n"
    }
}
