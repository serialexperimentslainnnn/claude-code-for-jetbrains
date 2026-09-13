package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.db.DbGateway
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DbTools(project: Project, private val gateway: DbGateway = DbGateway(project)) {

    fun domain(): ToolDomain? = if (DbGateway.isAvailable()) {
        ToolDomain(
            "db",
            "The data sources of the Database tool window: list them, read the schema the IDE introspected, run SQL over its connection",
            listOf(
                Tool(DB_CONNECTIONS, ::connections),
                Tool(DB_SCHEMA, ::schema),
                Tool(DB_QUERY) { ToolResult.toon(Batch.run(it, Batch.STATEMENTS, ::queryOne)) },
            ),
        )
    } else {
        null
    }

    private suspend fun connections(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val all = readAction { gateway.connections() }
        return ToolResult.toon(
            buildJsonObject {
                put("count", all.size)
                put("truncated", all.size > max)
                put(
                    "connections",
                    buildJsonArray {
                        all.take(max).forEach { c ->
                            add(
                                buildJsonObject {
                                    put("name", c.name)
                                    put("kind", c.kind)
                                    put("url", c.url)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun schema(args: ToolArgs): ToolResult {
        val connection = args.string("connection")
        val table = args.optionalString("table")
        val max = args.int("max", DEFAULT_MAX)
        val rows = readAction {
            if (table == null) gateway.tables(connection).map(::tableRow) else gateway.columns(connection, table).map(::columnRow)
        }
        return ToolResult.toon(
            buildJsonObject {
                put("connection", connection)
                put("table", table ?: "")
                put("count", rows.size)
                put("truncated", rows.size > max)
                put(if (table == null) "tables" else "columns", buildJsonArray { rows.take(max).forEach { add(it) } })
            },
        )
    }

    private fun tableRow(table: DbGateway.Table): JsonObject = buildJsonObject {
        put("schema", table.schema)
        put("name", table.name)
        put("kind", table.kind)
        put("columns", table.columns)
    }

    private fun columnRow(column: DbGateway.Column): JsonObject = buildJsonObject {
        put("name", column.name)
        put("type", column.type)
        put("nullable", column.nullable)
        put("primary", column.primary)
    }

    private suspend fun queryOne(args: ToolArgs): JsonObject {
        val connection = args.string("connection")
        val sql = args.string("code")
        val max = args.int("max", DEFAULT_MAX).coerceIn(1, MAX_ROWS)
        val result = runInterruptible(Dispatchers.IO) { gateway.query(connection, sql, max, QUERY_TIMEOUT_SECONDS) }
        val labels = labels(result.columns)
        return buildJsonObject {
            put("connection", connection)
            put("code", sql)
            put("count", result.rows.size)
            put("truncated", result.truncated)
            put("updated", result.updated)
            put("columns", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
            put(
                "rows",
                buildJsonArray {
                    result.rows.forEach { cells ->
                        add(buildJsonObject { labels.forEachIndexed { i, label -> put(label, cells[i].take(CELL_CHARS)) } })
                    }
                },
            )
        }
    }

    companion object {

        private const val DEFAULT_MAX = 100
        private const val MAX_ROWS = 1000
        private const val CELL_CHARS = 200
        private const val MILLIS = 1000L

        val DB_CONNECTIONS = ToolSpec(
            "db_connections",
            "Lists the data sources configured in the IDE's Database tool window: name, DBMS and JDBC URL with credentials " +
                "redacted. Call it first to learn the connection name the other db tools take.",
            listOf(Param("max", "Maximum data sources to return (default $DEFAULT_MAX)", type = "integer", required = false)),
        )

        val DB_SCHEMA = ToolSpec(
            "db_schema",
            "The schema the IDE has introspected for a data source: without table, its tables and views with schema, kind and " +
                "column count; with table (name or schema.name), that table's columns with type, nullability and primary key. " +
                "Empty until the IDE has connected and introspected the source.",
            listOf(
                Param("connection", "The data source name as db_connections lists it"),
                Param("table", "A table or view, as name or schema.name, to list its columns instead of the tables", required = false),
                Param("max", "Maximum rows to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val DB_QUERY = ToolSpec(
            "db_query",
            "Runs one SQL statement, or several in a row with statements, on a data source through the IDE's own connection, " +
                "driver and stored credentials, and returns its rows, or the update count when it returns none. Meant for " +
                "read-only queries; anything that writes is a change the user approves.",
            listOf(
                Param("connection", "The data source name as db_connections lists it"),
                Param("code", "The SQL statement to run", required = false),
                Batch.param(Batch.STATEMENTS, "Several SQL statements at once, one result each, on the same connection"),
                Param("max", "Maximum rows to return (default $DEFAULT_MAX, at most $MAX_ROWS)", type = "integer", required = false),
            ),
            mutates = true,
        )

        val QUERY_TIMEOUT_SECONDS: Int = (DB_QUERY.timeoutMillis / MILLIS).toInt()

        fun labels(columns: List<String>): List<String> {
            val seen = HashSet<String>()
            return columns.mapIndexed { i, label ->
                val base = label.ifBlank { "col${i + 1}" }
                var unique = base
                var n = 1
                while (!seen.add(unique)) unique = "${base}_${++n}"
                unique
            }
        }
    }
}
