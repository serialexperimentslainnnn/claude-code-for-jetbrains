package dev.lain.claudejb.view.payload.panel

import dev.lain.claudejb.controller.session.control.PlanInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JcefPlanData {

    fun planJson(plan: PlanInfo?): JsonObject? {
        val body = plan?.content?.trim().orEmpty()
        if (body.isEmpty()) return null
        return buildJsonObject {
            put("body", body)
            put("path", plan?.path?.takeIf { it.isNotBlank() })
        }
    }
}
