package dev.lain.claudejb.model.mcp.toon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ToonEncoder(private val options: ToonOptions) {

    private val delimiter = options.delimiter.symbol
    private val symbol = if (options.delimiter == ToonDelimiter.COMMA) "" else delimiter.toString()
    private val lines = ArrayList<String>()

    fun encode(value: JsonElement): String {
        when (value) {
            is JsonPrimitive -> lines += ToonText.primitive(value, delimiter)
            is JsonArray -> array("", value, 0)
            is JsonObject -> keyedColumns(value)?.let { keyed("", value, it, 0) } ?: fields(value, 0)
        }
        return lines.joinToString("\n")
    }

    private fun fields(obj: JsonObject, depth: Int) {
        for ((key, value) in obj) field(ToonText.key(key), value, depth)
    }

    private fun field(name: String, value: JsonElement, depth: Int) {
        when (value) {
            is JsonPrimitive -> emit(depth, "$name: ${ToonText.primitive(value, delimiter)}")
            is JsonArray -> array(name, value, depth)
            is JsonObject -> obj(name, value, depth)
        }
    }

    private fun obj(name: String, value: JsonObject, depth: Int) {
        val columns = keyedColumns(value)
        when {
            value.isEmpty() -> emit(depth, "$name:")

            columns != null -> keyed(name, value, columns, depth)

            else -> {
                emit(depth, "$name:")
                fields(value, depth + 1)
            }
        }
    }

    private fun array(name: String, arr: JsonArray, depth: Int) {
        val columns = if (arr.isNotEmpty() && arr.all { it is JsonObject }) columns(arr.map { it as JsonObject }) else null
        when {
            arr.isEmpty() -> emit(depth, if (name.isEmpty()) "[]" else "$name: []")

            arr.all { it is JsonPrimitive } -> emit(depth, "$name${bracket(arr.size)}: ${inline(arr)}")

            columns != null -> {
                emit(depth, "$name${bracket(arr.size)}{${ToonHeader.render(columns, delimiter)}}:")
                for (row in arr) emit(depth + 1, cells(row as JsonObject, columns).joinToString(delimiter.toString()))
            }

            else -> {
                emit(depth, "$name${bracket(arr.size)}:")
                for (element in arr) item(element, depth + 1)
            }
        }
    }

    private fun keyed(name: String, obj: JsonObject, columns: List<Field>, depth: Int) {
        emit(depth, "$name[${obj.size}:$symbol]{${ToonHeader.render(columns, delimiter)}}:")
        for ((key, value) in obj) {
            emit(depth + 1, "${ToonText.key(key)}: ${cells(value as JsonObject, columns).joinToString(delimiter.toString())}")
        }
    }

    private fun item(element: JsonElement, depth: Int) {
        when (element) {
            is JsonPrimitive -> emit(depth, "- ${ToonText.primitive(element, delimiter)}")
            is JsonArray -> arrayItem(element, depth)
            is JsonObject -> objectItem(element, depth)
        }
    }

    private fun arrayItem(element: JsonArray, depth: Int) {
        when {
            element.all { it is JsonPrimitive } -> {
                val values = if (element.isEmpty()) "" else " ${inline(element)}"
                emit(depth, "- ${bracket(element.size)}:$values")
            }

            else -> {
                emit(depth, "- ${bracket(element.size)}:")
                for (nested in element) item(nested, depth + 1)
            }
        }
    }

    private fun objectItem(element: JsonObject, depth: Int) {
        if (element.isEmpty()) {
            emit(depth, "-")
            return
        }
        val first = lines.size
        fields(element, depth + 1)
        lines[first] = indent(depth) + "- " + lines[first].substring(indent(depth + 1).length)
    }

    private fun columns(objects: List<JsonObject>): List<Field>? {
        val keys = objects.first().keys
        if (keys.isEmpty() || objects.any { it.keys != keys }) return null
        return keys.map { key -> column(key, objects.map { it.getValue(key) }) ?: return null }
    }

    private fun column(name: String, values: List<JsonElement>): Field? = when {
        values.all { it is JsonPrimitive } -> Field(name, null)
        values.all { it is JsonObject } -> columns(values.map { it as JsonObject })?.let { Field(name, it) }
        else -> null
    }

    private fun keyedColumns(obj: JsonObject): List<Field>? {
        if (obj.size < 2 || obj.values.any { it !is JsonObject }) return null
        return columns(obj.values.map { it as JsonObject })
    }

    private fun cells(obj: JsonObject, columns: List<Field>): List<String> {
        val out = ArrayList<String>()
        for (column in columns) {
            val value = obj.getValue(column.name)
            if (column.children == null) {
                out += ToonText.primitive(value as JsonPrimitive, delimiter)
            } else {
                out += cells(value as JsonObject, column.children)
            }
        }
        return out
    }

    private fun inline(arr: JsonArray): String =
        arr.joinToString(delimiter.toString()) { ToonText.primitive(it as JsonPrimitive, delimiter) }

    private fun bracket(size: Int): String = "[$size$symbol]"

    private fun emit(depth: Int, text: String) {
        lines += indent(depth) + text
    }

    private fun indent(depth: Int): String = " ".repeat(depth * options.indentSize)
}
