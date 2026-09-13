package dev.lain.claudejb.model.session.transcript

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

data class CardPlace(val label: String, val href: String)

object CardPlaces {

    private const val COMMIT_VIEW = "jb://toolwindow?id=Commit"

    private val FIXED: Map<String, CardPlace> = mapOf(
        "git_log" to CardPlace("View log", "jb://log"),
        "git_remote" to CardPlace("View log", "jb://log"),
        "git_status" to CardPlace("Open Commit view", COMMIT_VIEW),
        "git_stage" to CardPlace("Open Commit view", COMMIT_VIEW),
        "git_branches" to CardPlace("Branches", "jb://action?id=Git.Branches"),
        "git_branch" to CardPlace("Branches", "jb://action?id=Git.Branches"),
        "shell" to CardPlace("View in Terminal", "jb://terminal?tab=Claude"),
        "build" to CardPlace("View build", "jb://build"),
        "problems" to CardPlace("Open Problems", "jb://problems"),
        "project_problems" to CardPlace("Open Problems", "jb://problems"),
    )

    private val RUNS = setOf("run_configuration", "run_tests", "http_run")

    fun of(tool: String, args: JsonObject, result: JsonObject?): List<CardPlace> = listOfNotNull(
        when (tool) {
            "git_commit" -> text(result, "hash")?.let { CardPlace("View commit", "jb://commit?hash=" + encode(it)) }
            "git_diff" -> CardPlace("Open diff in IDE", text(args, "path")?.let { "jb://diff?file=" + encode(it) } ?: COMMIT_VIEW)
            in RUNS -> CardPlace("View run", text(args, "name")?.let { "jb://run?name=" + encode(it) } ?: "jb://toolwindow?id=Run")
            else -> FIXED[tool]
        },
    )

    private fun text(json: JsonObject?, key: String): String? =
        (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
