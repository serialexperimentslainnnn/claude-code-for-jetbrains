package dev.lain.claudejb.model.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object Batch {

    const val MAX_ITEMS = 50

    class Plural(val key: String, val identity: String, val items: Items = Items("string"), val alone: Set<String> = emptySet())

    private const val PATH = "File path, absolute or relative to the project root"

    val PATHS = Plural("paths", "path")
    val QUERIES = Plural("queries", "query")
    val NAMES = Plural("names", "name")
    val RUN_NAMES = Plural("names", "name", alone = setOf("job"))
    val RUN_PATHS = Plural("paths", "path", alone = setOf("job", "name"))
    val FILES = Plural("files", "path", Items("object", listOf(Param("path", PATH), Param("content", "Full content of the file"))))
    val POSITIONS = Plural(
        "positions",
        "path",
        Items(
            "object",
            listOf(
                Param("path", PATH),
                Param("line", "1-based line", type = "integer"),
                Param("column", "1-based column (default 1)", type = "integer", required = false),
            ),
        ),
    )
    val REPLACEMENTS = Plural(
        "edits",
        "path",
        Items(
            "object",
            listOf(
                Param("path", PATH),
                Param("old_string", "Exact text to replace"),
                Param("new_string", "Text that replaces it"),
                Param("replace_all", "true to replace every occurrence", type = "boolean", required = false),
            ),
        ),
    )
    val INSERTIONS = Plural(
        "edits",
        "path",
        Items(
            "object",
            listOf(
                Param("path", PATH),
                Param("line", "1-based line the content goes before", type = "integer"),
                Param("content", "Text to insert"),
            ),
        ),
    )

    val HASHES = Plural("hashes", "hash")
    val STATEMENTS = Plural("statements", "code")

    private val IDENTITIES: Map<String, String> =
        listOf(PATHS, QUERIES, NAMES, HASHES, STATEMENTS, FILES, POSITIONS, REPLACEMENTS).associate { it.key to it.identity }

    fun paths(what: String): Param = param(PATHS, "Several files at once, one result per path: $what")

    fun positions(what: String): Param = param(POSITIONS, "Several positions at once, one result per position: $what")

    fun param(plural: Plural, description: String): Param =
        Param(plural.key, description, type = "array", required = false, items = plural.items)

    val ONE_CALL_LISTS: Set<String> = setOf("git_stage", "git_commit", "search_replace", "shelve", "patch", "rollback", "delete_file")

    fun split(tool: String?, args: JsonObject): List<JsonObject>? {
        if (tool in ONE_CALL_LISTS) return null
        val (key, identity) = IDENTITIES.entries.firstOrNull { args[it.key] is JsonArray } ?: return null
        val shared = args.filterKeys { it != key }
        return (args.getValue(key) as JsonArray).map { item ->
            JsonObject(shared + (if (item is JsonObject) item else mapOf(identity to item)))
        }
    }

    fun itemId(toolUseId: String, index: Int): String = "$toolUseId#$index"

    suspend fun run(args: ToolArgs, plural: Plural, one: suspend (ToolArgs) -> JsonObject): JsonObject {
        val items = expand(args, plural) ?: return one(args)
        val rows = items.map { item -> row(item, plural.identity, one) }
        return buildJsonObject {
            put("count", rows.size)
            put("failed", rows.count { it.containsKey("error") })
            put("items", JsonArray(rows))
        }
    }

    fun expand(args: ToolArgs, plural: Plural): List<ToolArgs>? {
        val list = args.json[plural.key] ?: return null
        val elements = checked(args, plural, list)
        val base = args.json.filterKeys { it != plural.key }
        return elements.mapIndexed { index, item ->
            ToolArgs(JsonObject(base + element(item, plural)), args.toolUseId?.let { itemId(it, index) })
        }
    }

    private fun checked(args: ToolArgs, plural: Plural, list: JsonElement): JsonArray {
        val clash = (plural.alone + plural.identity).firstOrNull { args.json.containsKey(it) }
        val problem = when {
            clash != null -> "give $clash or ${plural.key}, not both"
            list !is JsonArray -> "argument ${plural.key} must be an array"
            list.isEmpty() -> "argument ${plural.key} must not be empty"
            list.size > MAX_ITEMS -> "argument ${plural.key} holds ${list.size} items; the ceiling is $MAX_ITEMS"
            else -> return list
        }
        throw ToolException(problem)
    }

    private fun element(element: JsonElement, plural: Plural): Map<String, JsonElement> = when {
        element is JsonPrimitive && element.isString -> mapOf(plural.identity to element)
        element is JsonObject -> element
        else -> throw ToolException("every item of ${plural.key} must be a string or an object")
    }

    private suspend fun row(item: ToolArgs, identity: String, one: suspend (ToolArgs) -> JsonObject): JsonObject {
        val id = item.optionalString(identity)
        val result = try {
            one(item)
        } catch (e: ToolException) {
            return buildJsonObject {
                put(identity, id ?: "")
                put("error", e.message ?: "failed")
            }
        }
        return buildJsonObject {
            if (id != null) put(identity, id)
            result.forEach { (key, value) -> if (key != identity || id == null) put(key, value) }
        }
    }
}
