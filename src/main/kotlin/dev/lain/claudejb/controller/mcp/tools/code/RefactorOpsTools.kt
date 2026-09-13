package dev.lain.claudejb.controller.mcp.tools.code

import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RefactorOpsTools(private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "refactor_ops",
        "The Refactor menu beyond rename, move and safe delete: introduce, extract, inline and the member refactorings, " +
            "each through the IDE's own refactoring with its dialog or in-place editor",
        listOf(Tool(INTRODUCE, ::introduce), Tool(EXTRACT, ::extract), Tool(INLINE, ::inline), Tool(MEMBERS, ::members)),
    )

    private suspend fun introduce(args: ToolArgs): ToolResult = fire("kind", INTRODUCE_ACTIONS, args)

    private suspend fun extract(args: ToolArgs): ToolResult = fire("kind", EXTRACT_ACTIONS, args)

    private suspend fun inline(args: ToolArgs): ToolResult = fire(null, mapOf("inline" to INLINE_ACTION), args)

    private suspend fun members(args: ToolArgs): ToolResult = fire("action", MEMBER_ACTIONS, args)

    private suspend fun fire(key: String?, table: Map<String, String>, args: ToolArgs): ToolResult {
        val name = key?.let { args.string(it) } ?: table.keys.single()
        val id = table[name] ?: throw ToolException("$key must be one of ${table.keys.joinToString()}")
        val target = TargetContext.target(args, preview = false)
        if (target.path == null) throw ToolException("$name needs path, line and column")
        val fired = dispatchFirstEnabled(listOf(id) + ALTERNATIVES[id].orEmpty(), target)
        return ToolResult.toon(
            buildJsonObject {
                put("refactoring", name)
                put("id", fired)
                put("path", target.path)
                put("line", target.line)
                put("column", target.column)
                put("selected", target.selection != null)
                put("dispatched", true)
            },
        )
    }

    private suspend fun dispatchFirstEnabled(ids: List<String>, target: TargetContext.Target): String {
        var refused: ToolException? = null
        for (id in ids) {
            try {
                actions.dispatch(id, target)
                return id
            } catch (e: ToolException) {
                refused = e
            }
        }
        throw refused ?: ToolException("no refactoring action to fire")
    }

    companion object {

        private const val INLINE_ACTION = "Inline"

        private val ALTERNATIVES: Map<String, List<String>> = mapOf("ExtractMethod" to listOf("ExtractFunction"))

        val INTRODUCE_ACTIONS: Map<String, String> = linkedMapOf(
            "variable" to "IntroduceVariable",
            "constant" to "IntroduceConstant",
            "field" to "IntroduceField",
            "parameter" to "IntroduceParameter",
            "functional_parameter" to "IntroduceFunctionalParameter",
        )

        val EXTRACT_ACTIONS: Map<String, String> = linkedMapOf(
            "method" to "ExtractMethod",
            "interface" to "ExtractInterface",
            "superclass" to "ExtractSuperclass",
            "delegate" to "ExtractClass",
            "module" to "ExtractModule",
        )

        val MEMBER_ACTIONS: Map<String, String> = linkedMapOf(
            "pull_up" to "MembersPullUp",
            "push_down" to "MemberPushDown",
            "change_signature" to "ChangeSignature",
            "move" to "Move",
            "encapsulate_fields" to "EncapsulateFields",
            "make_static" to "MakeStatic",
            "convert_to_instance" to "ConvertToInstanceMethod",
            "inheritance_to_delegation" to "InheritanceToDelegation",
            "anonymous_to_inner" to "AnonymousToInner",
            "method_object" to "ReplaceMethodWithMethodObject",
        )

        private val POSITION = listOf(
            Param("path", "File path, absolute or relative to the project root"),
            Param("line", "1-based line of the caret, or where the selection starts", type = "integer"),
            Param("column", "1-based column of the caret (default 1)", type = "integer", required = false),
        ) + TargetContext.SELECTION_PARAMS

        val INTRODUCE = ToolSpec(
            "introduce",
            "Refactor ▸ Introduce on the expression at a position or in a selection (line..to_line): variable, constant, " +
                "field, parameter or functional_parameter. The IDE's own refactoring runs, in place or with its dialog, " +
                "for the user to name and confirm; the file opens in a tab without focus.",
            listOf(Param("kind", "variable, constant, field, parameter or functional_parameter")) + POSITION,
            mutates = true,
        )

        val EXTRACT = ToolSpec(
            "extract",
            "Refactor ▸ Extract on a selection or the element at a position: method, interface, superclass, delegate " +
                "(Extract Class) or module. The IDE's dialog opens for the user to finish.",
            listOf(Param("kind", "method, interface, superclass, delegate or module")) + POSITION,
            mutates = true,
        )

        val INLINE = ToolSpec(
            "inline",
            "Refactor ▸ Inline on the symbol at a position (variable, method, class…): the IDE's dialog opens for the user " +
                "to confirm the scope.",
            POSITION,
            mutates = true,
        )

        val MEMBERS = ToolSpec(
            "members",
            "A member refactoring on the element at a position, through the IDE's dialog: pull_up, push_down, " +
                "change_signature, move, encapsulate_fields, make_static, convert_to_instance, inheritance_to_delegation, " +
                "anonymous_to_inner or method_object (the Java ones are refused where the language has no such refactoring).",
            listOf(Param("action", "One of the names above")) + POSITION,
            mutates = true,
        )
    }
}
