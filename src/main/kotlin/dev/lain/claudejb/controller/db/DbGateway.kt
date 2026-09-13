package dev.lain.claudejb.controller.db

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.util.PluginIds
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class DbGateway(private val project: Project) {

    class Connection(val name: String, val kind: String, val url: String)

    class Table(val schema: String, val name: String, val kind: String, val columns: Int)

    class Column(val name: String, val type: String, val nullable: Boolean, val primary: Boolean)

    class Rows(val columns: List<String>, val rows: List<List<String>>, val updated: Int, val truncated: Boolean)

    private fun requireDatabase() {
        if (!isAvailable()) throw ToolException(MISSING)
    }

    fun connections(): List<Connection> {
        requireDatabase()
        return dataSources().map { source ->
            Connection(name(source), text { call(source, "getDbms") }, redacted(text { call(delegate(source), "getUrl") }))
        }
    }

    fun tables(connection: String): List<Table> {
        requireDatabase()
        return tablesOf(dataSource(connection)).map { table ->
            Table(schema(table), name(table), text { call(table, "getKind") }.lowercase(), columnsOf(table).size)
        }
    }

    fun columns(connection: String, table: String): List<Column> {
        requireDatabase()
        val found = tablesOf(dataSource(connection)).filter { matches(it, table) }
        if (found.isEmpty()) throw ToolException("no table named $table in $connection; db_schema without table lists them")
        if (found.size > 1) throw ToolException("$table is ambiguous; qualify it: ${found.joinToString { qualified(it) }}")
        return columnsOf(found.single()).map { column ->
            Column(
                name(column),
                text { typeName(call(column, "getDataType")) },
                !flag { call(column, "isNotNull") },
                flag { static(dasUtil(), "isPrimary", column) },
            )
        }
    }

    fun query(connection: String, sql: String, maxRows: Int, timeoutSeconds: Int): Rows {
        requireDatabase()
        val source = dataSource(connection)
        val builder = call(static(type(CONNECTION_MANAGER), "getInstance"), "build", project, delegate(source))
        runCatching { call(builder, "setAskPassword", false) }
        val ref = call(builder, "create")
            ?: throw ToolException("the IDE could not connect to $connection; connect it in the Database tool window first")
        try {
            val statement = call(call(call(ref, "get"), "getRemoteConnection"), "createStatement")
            try {
                call(statement, "setQueryTimeout", timeoutSeconds)
                call(statement, "setMaxRows", maxRows + 1)
                val produced = call(statement, "execute", sql) == true
                return if (produced) rows(call(statement, "getResultSet"), maxRows) else updated(statement)
            } finally {
                runCatching { call(statement, "close") }
            }
        } finally {
            runCatching { call(ref, "close") }
        }
    }

    private fun updated(statement: Any?): Rows = Rows(emptyList(), emptyList(), (call(statement, "getUpdateCount") as? Int) ?: 0, false)

    private fun rows(resultSet: Any?, maxRows: Int): Rows {
        try {
            val meta = call(resultSet, "getMetaData")
            val width = (call(meta, "getColumnCount") as? Int) ?: 0
            val labels = (1..width).map { text { call(meta, "getColumnLabel", it) } }
            val collected = ArrayList<List<String>>()
            while (collected.size <= maxRows && call(resultSet, "next") == true) {
                collected += (1..width).map { text { call(resultSet, "getString", it) } }
            }
            return Rows(labels, collected.take(maxRows), 0, collected.size > maxRows)
        } finally {
            runCatching { call(resultSet, "close") }
        }
    }

    private fun dataSources(): List<Any> {
        val facade = static(type(FACADE), "getInstance", project)
        return items(call(facade, "getDataSources"))
    }

    private fun dataSource(connection: String): Any {
        val sources = dataSources()
        return sources.firstOrNull { name(it).equals(connection, ignoreCase = true) }
            ?: throw ToolException(
                if (sources.isEmpty()) {
                    "the Database tool window has no data sources; add one there first"
                } else {
                    "no data source named $connection; the data sources are ${sources.joinToString { name(it) }}"
                },
            )
    }

    private fun delegate(source: Any): Any =
        call(source, "getDelegateDataSource") ?: throw ToolException(notExposed("getDelegateDataSource"))

    private fun tablesOf(source: Any): List<Any> = items(static(dasUtil(), "getTables", source))

    private fun columnsOf(table: Any): List<Any> = items(static(dasUtil(), "getColumns", table))

    private fun items(iterable: Any?): List<Any> = (iterable as? Iterable<*>)?.toList().orEmpty().filterNotNull()

    private fun schema(table: Any): String = text { static(dasUtil(), "getSchema", table) }

    private fun qualified(table: Any): String = schema(table).let { if (it.isEmpty()) name(table) else "$it.${name(table)}" }

    private fun matches(table: Any, wanted: String): Boolean =
        name(table).equals(wanted, ignoreCase = true) || qualified(table).equals(wanted, ignoreCase = true)

    private fun name(target: Any): String = text { call(target, "getName") }

    private fun dasUtil(): Class<*> = type(DAS_UTIL)

    private fun text(read: () -> Any?): String = runCatching(read).getOrNull()?.toString().orEmpty()

    private fun flag(read: () -> Any?): Boolean = runCatching(read).getOrNull() == true

    private fun typeName(dataType: Any?): Any? = dataType?.javaClass?.getField("typeName")?.get(dataType)

    private fun type(name: String): Class<*> =
        loaders().firstNotNullOfOrNull { loader -> runCatching { loader.loadClass(name) }.getOrNull() }
            ?: throw ToolException(notExposed(name))

    private fun loaders(): List<ClassLoader> {
        val modules = ServiceViewContributor.CONTRIBUTOR_EP_NAME.extensionList
            .filter { it.javaClass.name.startsWith(PACKAGE) }
            .map { it.javaClass.classLoader }
        return (listOf(javaClass.classLoader) + modules).distinct()
    }

    private fun static(type: Class<*>, name: String, vararg args: Any?): Any? {
        val method = type.methods.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) && accepts(it, args) }
            ?: throw ToolException(notExposed("${type.simpleName}.$name"))
        return invoke(method, null, args)
    }

    private fun call(target: Any?, name: String, vararg args: Any?): Any? {
        val receiver = target ?: throw ToolException(notExposed(name))
        val method = receiver.javaClass.methods.firstOrNull { it.name == name && accepts(it, args) }
            ?: throw ToolException(notExposed(name))
        return invoke(method, receiver, args)
    }

    private fun accepts(method: Method, args: Array<out Any?>): Boolean =
        method.parameterCount == args.size &&
            method.parameterTypes.zip(args).all { (type, arg) -> arg == null || type.kotlin.javaObjectType.isInstance(arg) }

    @Suppress("SpreadOperator")
    private fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): Any? = try {
        method.isAccessible = true
        method.invoke(receiver, *args)
    } catch (e: InvocationTargetException) {
        throw ToolException(e.cause?.message ?: "${method.name} failed inside the database plugin", e)
    } catch (e: IllegalAccessException) {
        throw ToolException(notExposed(method.name), e)
    }

    private fun redacted(url: String): String =
        PASSWORD_PARAM.replace(USER_INFO.replace(url, "//")) { it.groupValues[1] + "=***" }

    companion object {

        const val PLUGIN_ID = "com.intellij.database"
        private const val PACKAGE = "com.intellij.database."

        private const val FACADE = "com.intellij.database.psi.DbPsiFacade"
        private const val DAS_UTIL = "com.intellij.database.util.DasUtil"
        private const val CONNECTION_MANAGER = "com.intellij.database.dataSource.DatabaseConnectionManager"
        private const val MISSING = "the Database plugin ($PLUGIN_ID) is not loaded in this IDE; the db tools need " +
            "IntelliJ IDEA Ultimate, PyCharm Professional, DataGrip or another IDE that bundles it"

        private val USER_INFO = Regex("//[^/@\\s]*@")
        private val PASSWORD_PARAM = Regex("(?i)(password|pwd|passwd)=[^&;]*")

        fun isAvailable(): Boolean = PluginManagerCore.isLoaded(PluginIds.of(PLUGIN_ID))

        private fun notExposed(what: String): String =
            "the database plugin of this IDE does not expose $what; open the Database tool window and work there"
    }
}
