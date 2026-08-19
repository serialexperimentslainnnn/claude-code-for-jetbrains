package dev.lain.claudejb.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object AgentEnding {

    enum class Ending {
        COMPLETED,

        RESUMED,

        ABORTED,

        UNFINISHED,
    }

    fun of(records: List<JsonObject>): Ending? {
        if (records.isEmpty()) return null
        if (records.last().isAbortMarker()) return Ending.ABORTED
        val lastFinished = records.indexOfLast { it.endsTurn() }
        return when {
            lastFinished == records.lastIndex -> Ending.COMPLETED
            records.last().isFinalAnswer() -> Ending.COMPLETED
            lastFinished >= 0 -> Ending.RESUMED
            else -> Ending.UNFINISHED
        }
    }

    private fun JsonObject.endsTurn(): Boolean {
        val message = this["message"] as? JsonObject ?: return false
        return (message["stop_reason"] as? JsonPrimitive)?.contentOrNull == "end_turn"
    }

    private fun JsonObject.isAbortMarker(): Boolean = isUserInterrupt() || isBinaryCutOff()

    private fun JsonObject.isUserInterrupt(): Boolean =
        (this["type"] as? JsonPrimitive)?.contentOrNull == "user" &&
            text().trimStart().startsWith(INTERRUPT_PREFIX)

    private fun JsonObject.isBinaryCutOff(): Boolean {
        if ((this["type"] as? JsonPrimitive)?.contentOrNull != "assistant") return false
        val message = this["message"] as? JsonObject ?: return false
        return (message["model"] as? JsonPrimitive)?.contentOrNull == SYNTHETIC_MODEL
    }

    private fun JsonObject.text(): String {
        val content = (this["message"] as? JsonObject)?.get("content") ?: return ""
        (content as? JsonPrimitive)?.contentOrNull?.let { return it }
        val blocks = content as? JsonArray ?: return ""
        return blocks.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
    }

    private fun JsonObject.isFinalAnswer(): Boolean {
        if ((this["type"] as? JsonPrimitive)?.contentOrNull != "assistant") return false
        val message = this["message"] as? JsonObject ?: return false
        if ((message["stop_reason"] as? JsonPrimitive)?.contentOrNull != null) return false
        val content = message["content"] as? JsonArray ?: return false
        return content.none { (it as? JsonObject)?.get("type").let { t -> t as? JsonPrimitive }?.contentOrNull == "tool_use" }
    }

    private const val SYNTHETIC_MODEL = "<synthetic>"

    private const val INTERRUPT_PREFIX = "[Request interrupted"
}
