package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.context.ProjectTree
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JcefTreeData {

    fun childrenJson(path: String, mode: String, entries: List<ProjectTree.Entry>): JsonObject =
        buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("truncated", entries.size >= ProjectTree.MAX_ENTRIES)
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { entry ->
                        addJsonObject {
                            put("name", entry.name)
                            put("path", entry.path)
                            put("directory", entry.directory)
                        }
                    }
                },
            )
        }

    fun expansionJson(path: String, mode: String, expansion: ProjectTree.Expansion): JsonObject =
        buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("truncated", expansion.truncated)
            put("paths", buildJsonArray { expansion.paths.forEach { add(it) } })
        }
}
