package dev.lain.claudejb.model.session.turn

import dev.lain.claudejb.model.protocol.models.AuthStatusInfo
import dev.lain.claudejb.model.protocol.models.ContextUsage
import dev.lain.claudejb.model.protocol.models.RateLimitInfo
import kotlinx.serialization.json.JsonObject

class SessionSignals {

    @Volatile var rateLimit: RateLimitInfo? = null
        internal set

    @Volatile var rateLimits: Map<String, RateLimitInfo> = emptyMap()
        internal set

    @Volatile var sessionState: String? = null
        internal set

    @Volatile var authStatus: AuthStatusInfo? = null
        internal set

    @Volatile var lastSessionCost: JsonObject? = null
        internal set

    @Volatile var lastContextUsage: ContextUsage? = null
        internal set
}
