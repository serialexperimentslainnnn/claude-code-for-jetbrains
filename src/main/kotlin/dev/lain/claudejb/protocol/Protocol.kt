package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Single tolerant [Json] instance for the whole stream-json protocol.
 *
 * The Claude Code control protocol is broad (dozens of message and control subtypes, many of which
 * this plugin ignores) and evolves between binary versions, so decoding is deliberately lenient:
 * unknown keys/types must never crash the reader loop.
 *
 * Incoming messages are decoded with the typed models below; outgoing messages are built explicitly
 * as [kotlinx.serialization.json.JsonObject]s in [ControlProtocol] to keep their wire shape exact.
 */
val ClaudeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// Incoming SDKMessage payloads (subset we care about). Verified against
// node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts (claudeCodeVersion 2.1.150).
// ---------------------------------------------------------------------------

/** `{"type":"system","subtype":"init", ...}` — first message; carries the session id to --resume. */
@Serializable
data class SystemInit(
    @SerialName("session_id") val sessionId: String = "",
    val model: String = "",
    val cwd: String = "",
    val tools: List<String> = emptyList(),
    @SerialName("slash_commands") val slashCommands: List<String> = emptyList(),
    @SerialName("permissionMode") val permissionMode: String = "default",
    @SerialName("mcp_servers") val mcpServers: List<McpServerStatus> = emptyList(),
    @SerialName("output_style") val outputStyle: String = "default",
    @SerialName("claude_code_version") val claudeCodeVersion: String = "",
)

@Serializable
data class McpServerStatus(val name: String = "", val status: String = "")

/**
 * `{"type":"result","subtype":"success|error_*", ...}` — end of a turn. Watching for this is how
 * the host knows the agent is idle again and can flush the next queued (multiprompt) message.
 */
@Serializable
data class ResultMessage(
    val subtype: String = "",
    @SerialName("is_error") val isError: Boolean = false,
    val result: String = "",
    // error_* subtypes carry no `result`; their message(s) arrive here (sdk.d.ts SDKResultError.errors).
    val errors: List<String> = emptyList(),
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("total_cost_usd") val totalCostUsd: Double = 0.0,
    @SerialName("num_turns") val numTurns: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

/** Inner Anthropic BetaMessage of `{"type":"assistant","message":{...}}`. Content blocks are dispatched manually. */
@Serializable
data class AssistantInner(
    val id: String = "",
    val model: String = "",
    val role: String = "assistant",
    val content: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

// ---------------------------------------------------------------------------
// Control protocol: can_use_tool request (binary -> host) and its data.
// ---------------------------------------------------------------------------

/** Inner payload of a `can_use_tool` control_request. The hook that drives native diff review. */
@Serializable
data class CanUseToolRequest(
    @SerialName("tool_name") val toolName: String = "",
    val input: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("blocked_path") val blockedPath: String? = null,
    /** Explains why this permission request was triggered (e.g. a deny rule, an out-of-root path). */
    @SerialName("decision_reason") val decisionReason: String? = null,
)

// ---------------------------------------------------------------------------
// AskUserQuestion: a built-in tool delivered as a can_use_tool whose input carries
// the questions. The host renders them and replies allow with updatedInput = input +
// {"answers": {questionText: chosenLabel}} (multi-select labels are comma-joined).
// Verified empirically against claude 2.1.150 (the result echoes the chosen option).
// ---------------------------------------------------------------------------

@Serializable
data class AskQuestion(
    val question: String = "",
    val header: String = "",
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false,
)

@Serializable
data class AskOption(
    val label: String = "",
    val description: String = "",
    val preview: String? = null,
)

/** Parses the `questions` array out of an AskUserQuestion tool input; empty if malformed. */
fun parseAskQuestions(input: kotlinx.serialization.json.JsonObject): List<AskQuestion> {
    val arr = input["questions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return runCatching {
        arr.map { ClaudeJson.decodeFromJsonElement(AskQuestion.serializer(), it) }
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// elicitation control request (binary -> host): an MCP server asks the user for
// input. URL mode points at a link to complete (e.g. an OAuth flow); form mode
// carries a JSON-schema `requested_schema` whose primitive properties become input
// fields. Answered with ElicitResult {action: accept|decline|cancel, content?}.
// Verified against SDKControlElicitationRequest in sdk.d.ts.
// ---------------------------------------------------------------------------

@Serializable
data class ElicitationRequest(
    @SerialName("mcp_server_name") val mcpServerName: String = "",
    val message: String = "",
    val mode: String? = null, // "form" | "url" | null
    val url: String? = null,
    @SerialName("elicitation_id") val elicitationId: String? = null,
    @SerialName("requested_schema") val requestedSchema: JsonObject? = null,
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
)

/** One primitive input field extracted from an elicitation's requested_schema. */
data class ElicitField(
    val name: String,
    val type: String, // string | number | integer | boolean
    val title: String?,
    val required: Boolean,
)

private val PRIMITIVE_ELICIT_TYPES = setOf("string", "number", "integer", "boolean")

/**
 * Extracts the flat primitive fields of an elicitation `requested_schema` (a JSON-schema object): one
 * [ElicitField] per `properties` entry whose `type` is string/number/integer/boolean. Returns an empty list
 * when the schema is absent, malformed, or carries any nested/object property — the caller then falls back to
 * a plain Accept-with-no-content card. Never throws.
 */
fun parseElicitationFields(schema: JsonObject?): List<ElicitField> {
    schema ?: return emptyList()
    return runCatching {
        val props = schema["properties"] as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet().orEmpty()
        val fields = ArrayList<ElicitField>(props.size)
        for ((name, spec) in props) {
            val obj = spec as? JsonObject ?: return emptyList()
            val type = obj.str("type") ?: return emptyList()
            if (type !in PRIMITIVE_ELICIT_TYPES) return emptyList()
            fields += ElicitField(name, type, obj.str("title"), name in required)
        }
        fields
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// initialize handshake response (binary -> host): rich command + model metadata.
// ---------------------------------------------------------------------------

@Serializable
data class InitializeResponse(
    val commands: List<SlashCommand> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    @SerialName("output_style") val outputStyle: String = "default",
    @SerialName("available_output_styles") val availableOutputStyles: List<String> = emptyList(),
    val account: AccountInfo = AccountInfo(),
)

@Serializable
data class AgentInfo(
    val name: String = "",
    val description: String = "",
)

@Serializable
data class AccountInfo(
    val email: String = "",
    val organization: String = "",
    val subscriptionType: String = "",
    /** Auth backend reported by the binary (firstParty/bedrock/vertex/foundry/anthropicAws/mantle/gateway). */
    val apiProvider: String = "",
    /** Where the API key (if any) came from (e.g. env var, helper script). */
    val apiKeySource: String = "",
)

/** A slash command as reported by the binary: name (no slash), description, argument hint, aliases. */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val aliases: List<String> = emptyList(),
)

@Serializable
data class ModelInfo(
    val value: String,
    val displayName: String = "",
    val description: String = "",
    /** Whether `--effort` is meaningful for this model (Opus 4.7+ supports it; Haiku does not). */
    val supportsEffort: Boolean = false,
    /** Effort levels the model accepts (e.g. ["low","medium","high","xhigh","max"]). */
    val supportedEffortLevels: List<String> = emptyList(),
    /** Whether adaptive extended thinking is supported (drives `--thinking adaptive`). */
    val supportsAdaptiveThinking: Boolean = false,
    /** Whether the model supports the binary's "fast mode" (no reasoning, lowest latency). */
    val supportsFastMode: Boolean = false,
    /** Whether the model supports "auto mode" (binary picks effort/thinking per turn). */
    val supportsAutoMode: Boolean = false,
)

// ---------------------------------------------------------------------------
// get_context_usage response (binary -> host): drives the context meter (/context).
// ---------------------------------------------------------------------------

@Serializable
data class ContextUsage(
    val totalTokens: Long = 0,
    val maxTokens: Long = 0,
    val percentage: Double = 0.0,
    val categories: List<ContextCategory> = emptyList(),
)

@Serializable
data class ContextCategory(
    val name: String = "",
    val tokens: Long = 0,
)

/**
 * `apiUsage` block of the `get_session_cost` control response: the binary's **authoritative cumulative**
 * token tally for the session (the same figures the Anthropic API returns). Preferred over locally-folded
 * counters for display, which can drift. Matches `SDKControlGetSessionCostResponse.apiUsage` in sdk.d.ts.
 */
@Serializable
data class SessionCostUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
)

// ---------------------------------------------------------------------------
// rate_limit_event (binary -> host): subscription quota usage for claude.ai users.
// `utilization` is only present when the binary has it (typically near the limit).
// ---------------------------------------------------------------------------

@Serializable
data class RateLimitInfo(
    val status: String = "allowed", // allowed | allowed_warning | rejected
    val resetsAt: Long? = null, // epoch seconds when this window resets
    val rateLimitType: String? = null, // five_hour | seven_day | seven_day_opus/sonnet | overage
    val utilization: Double? = null, // FRACTION of quota used, 0..1 — see utilizationPercent()
    val overageStatus: String? = null,
    val isUsingOverage: Boolean = false,
    // Both of these ARE on the wire and were previously dropped — confirmed by capturing a live
    // rate_limit_event, which carried `overageResetsAt` and `overageInUse` alongside the fields above.
    // `overageResetsAt` is the only way to tell the user when overage billing rolls over.
    val overageResetsAt: Long? = null,
    val overageInUse: Boolean = false,
    val surpassedThreshold: Double? = null,
) {
    /** Clamped 0..100 percent, or null if the binary didn't report utilization. */
    fun utilizationPercent(): Int? =
        utilization?.let { Math.round(it * PERCENT).toInt().coerceIn(0, 100) }

    /**
     * [resetsAt] as ISO-8601, the shape `get_usage` already uses, so a window sourced from an EVENT and one
     * sourced from the REPORT are interchangeable to every surface that renders a countdown. The conversion
     * lives on the model because both UI builders need it and two copies would be two chances to drift.
     */
    fun resetsAtIso(): String? = resetsAt?.let { java.time.Instant.ofEpochSecond(it).toString() }

    val isWarning: Boolean get() = status == "allowed_warning" || status == "rejected"
    val isExhausted: Boolean get() = status == "rejected"

    /**
     * SHORT window label for the composer's quota pill (e.g. "5h", "7d"), where there is room for a few
     * characters and no more. The dashboard wants a sentence instead — see [windowTitleFor].
     */
    fun windowLabel(): String = when (rateLimitType) {
        "five_hour" -> "5h"
        "seven_day" -> "7d"
        "seven_day_opus" -> "7d Opus"
        "seven_day_sonnet" -> "7d Sonnet"
        "overage" -> "overage"
        else -> "quota"
    }

    companion object {
        /** The event's fraction → percent. [UsageWindow] needs no such factor: it is already 0..100. */
        private const val PERCENT = 100

        /**
         * DESCRIPTIVE label for a window, for the usage panel — where each bar needs to say what it measures.
         * Deliberately separate from [windowLabel]: the pill is space-constrained and the panel is not, and
         * collapsing the two is what broke the pill the first time this was written.
         *
         * Unknown keys are title-cased rather than dropped. The binary keeps adding windows
         * (`seven_day_cowork`, `seven_day_omelette`, and several codenamed ones), and a window we cannot label
         * is still a window the user is being limited by — showing it as-is beats hiding it.
         */
        fun windowTitleFor(type: String?): String = when (type) {
            "five_hour" -> "Current session"
            "seven_day" -> "All models"
            "seven_day_opus" -> "Opus"
            "seven_day_sonnet" -> "Sonnet"
            "seven_day_oauth_apps" -> "OAuth apps"
            "overage" -> "Overage"
            null, "" -> "Quota"
            else -> type.removePrefix("seven_day_").replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * One usage window from the `get_usage` control reply, e.g. `rate_limits.five_hour`.
 *
 * Note the shape difference from [RateLimitInfo], which models the *event*: here `resets_at` is an ISO-8601
 * string, not epoch seconds, and the dollar fields exist for plans that are billed rather than throttled.
 */
@Serializable
data class UsageWindow(
    val utilization: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("limit_dollars") val limitDollars: Double? = null,
    @SerialName("used_dollars") val usedDollars: Double? = null,
    @SerialName("remaining_dollars") val remainingDollars: Double? = null,
    // Only the `model_scoped` entries carry this. It is the SERVER's own label for the bucket ("Fable"), and
    // the only thing that names them: their key is synthesised here, so there is nothing to derive a title
    // from the way `seven_day_opus` derives "Opus".
    @SerialName("display_name") val displayName: String? = null,
) {
    /**
     * How this window is titled in the UI: the server's own label when it sent one, else the key's.
     *
     * Every surface (dashboard bar, composer dot, quota warning) goes through here, so a per-model window
     * cannot end up correctly labelled in one place and titled from its synthetic key in another.
     */
    fun title(key: String): String =
        displayName?.takeIf { it.isNotBlank() } ?: RateLimitInfo.windowTitleFor(key)

    /**
     * The window's percentage, 0..100, or null when the binary reported none.
     *
     * **THE SCALE IS ALREADY A PERCENTAGE HERE, and this is the whole point of the function existing.**
     * `sdk.d.ts` documents every `get_usage` window as *"Percentage of the window used, 0-100"* — unlike
     * [RateLimitInfo.utilization] on the *event*, which is a 0..1 fraction. The two really do differ, and
     * conflating them is not a rounding error but a factor of a hundred in either direction.
     *
     * There is deliberately **no "accept 0..1 too" heuristic**. It is undecidable at exactly 1.0, and it
     * decided wrong: a window at a genuine **1%** was read as a fraction and reported as **100%**, which
     * fired the 85% quota notification — telling the user their plan was spent at the moment they had spent
     * almost none of it. Reachable by every user at the start of every freshly reset window, and it survived
     * as a private copy in `ClaudeSession` after the same rule had been removed from the two display paths.
     * Hence one function, on the model, for every caller.
     */
    fun utilizationPercent(): Int? =
        utilization?.let { Math.round(it).toInt().coerceIn(0, PERCENT) }

    // NOT a private companion: kotlinx generates `serializer()` ON the companion, and `parseUsageReport`
    // calls `UsageWindow.serializer()` by hand — making it private hides the generated accessor with it.
    companion object {
        private const val PERCENT = 100
    }
}

/** `rate_limits.extra_usage` — the pay-as-you-go credit balance shown once the plan's windows are spent. */
@Serializable
data class ExtraUsage(
    @SerialName("is_enabled") val isEnabled: Boolean = false,
    @SerialName("monthly_limit") val monthlyLimit: Double? = null,
    @SerialName("used_credits") val usedCredits: Double? = null,
    val utilization: Double? = null,
    val currency: String? = null,
    @SerialName("decimal_places") val decimalPlaces: Int = 2,
    @SerialName("spend_limit_reached") val spendLimitReached: Boolean = false,
    @SerialName("user_disabled") val userDisabled: Boolean = false,
)

/**
 * The `get_usage` control reply, flattened into what a UI actually needs.
 *
 * [windows] is a LIST, not a map, because order is meaning here: the session window first, then the weekly
 * all-models one, then the per-model buckets. Windows the binary reports as `null` (a limit that exists but
 * has not been touched) are dropped rather than rendered as 0% — "untouched" and "zero used" look identical
 * on a progress bar and are not the same claim.
 */
data class UsageReport(
    val subscriptionType: String? = null,
    val available: Boolean = false,
    val windows: List<Pair<String, UsageWindow>> = emptyList(),
    val extra: ExtraUsage? = null,
) {
    val isEmpty: Boolean get() = windows.isEmpty() && extra == null
}

/** Windows first in this order; anything the binary adds later sorts after, in the order it sent them. */
private val USAGE_WINDOW_ORDER = listOf("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet")

/**
 * Windows dropped before they reach any surface — dashboard bar, composer dot or quota warning.
 *
 * **This is a deliberate exception to the rule right below it**, which is that an unknown window is still a
 * limit the user is subject to and gets shown with a derived label rather than hidden. `nimbus_quill` is a
 * key the claude.ai usage endpoint emits and the CLI relays untouched — it appears in no version of the
 * binary (grepped: zero occurrences) and in no SDK type, so nothing here can say what it meters. It rendered
 * as "Nimbus quill 0.0%", which is a row that asks a question and answers none.
 *
 * **Temporary, and keyed by name so it stays cheap to undo**: the moment that window means something to a
 * user, delete the entry and it comes back with everything else it already has — ordering, label, bar.
 * Deliberately NOT generalised into "hide unknown windows", which would silently swallow the next real limit
 * Anthropic ships.
 *
 * Applied on BOTH paths that feed a window to the UI — the `get_usage` report here and the `rate_limit_event`
 * stream in `ClaudeSession.onRateLimit`, which is why it is public. Filtering only the report left the row on
 * screen anyway, arriving by the other door.
 */
private val HIDDEN_WINDOWS = setOf("nimbus_quill")

/** Whether [window] is one of the [HIDDEN_WINDOWS] the UI never shows. */
fun isHiddenUsageWindow(window: String?): Boolean = window in HIDDEN_WINDOWS

/**
 * Prefix of the synthetic key given to a `model_scoped` window, whose real identity is its `display_name`.
 *
 * Unlike every other window, these arrive in a JSON **array** with no key of their own, so one is made here.
 * It has to be stable across refreshes — the "announce a quota crossing once" record is kept per key — and it
 * has to be namespaced, so a bucket the server one day labels "Overage" cannot collide with the top-level
 * window of that name.
 */
const val MODEL_SCOPED_KEY_PREFIX = "model_scoped:"

/**
 * Parses a `get_usage` reply. Returns null when the payload is absent or carries nothing worth showing.
 *
 * `rate_limits` is a heterogeneous object — window entries, explicit nulls for untouched windows, the
 * `model_scoped` ARRAY, and `extra_usage` with an entirely different shape — so it is walked by hand rather
 * than deserialized whole. A window that fails to decode is skipped, never thrown on: this feeds a dashboard,
 * and one unrecognised key from a newer binary must not blank the whole panel.
 */
fun parseUsageReport(payload: JsonObject?): UsageReport? {
    payload ?: return null
    val limits = payload["rate_limits"] as? JsonObject
    val extra = (limits?.get("extra_usage") as? JsonObject)?.let {
        runCatching { ClaudeJson.decodeFromJsonElement(ExtraUsage.serializer(), it) }.getOrNull()
    }
    val keyed = limits.orEmpty().mapNotNull { (key, value) ->
        if (key == "extra_usage" || key == MODEL_SCOPED) return@mapNotNull null
        if (key in HIDDEN_WINDOWS) return@mapNotNull null
        val obj = value as? JsonObject ?: return@mapNotNull null // null = window exists but untouched
        decodeWindow(obj)?.let { key to it }
    }
    val windows = sortUsageWindows(keyed + perModelWindows(limits, keyed))
    val report = UsageReport(
        subscriptionType = payload.str("subscription_type"),
        available = (payload["rate_limits_available"] as? JsonPrimitive)?.booleanOrNull ?: false,
        windows = windows,
        extra = extra?.takeIf { it.isEnabled },
    )
    return report.takeUnless { it.isEmpty }
}

/** Known windows lead, in [USAGE_WINDOW_ORDER]; anything else trails in the order it arrived (stable sort). */
private fun sortUsageWindows(windows: List<Pair<String, UsageWindow>>): List<Pair<String, UsageWindow>> =
    windows.sortedBy { (key, _) ->
        USAGE_WINDOW_ORDER.indexOf(key).takeIf { it >= 0 } ?: USAGE_WINDOW_ORDER.size
    }

/**
 * This report over a [previous] one: fresh windows win, windows it does not mention are carried forward.
 *
 * **A refresh that omits a window is not a claim that the window is gone**, and treating it as one made the
 * per-model bars blink in and out every poll. The cause is in the binary and is structural: `loadPlanRateLimits`
 * fetches `/api/oauth/usage` with a 5 s timeout, and on a timeout, a 429 or a fieldless body it falls back to
 * `seedUtilization()` — an object rebuilt from the rate-limit *response headers*, which by construction can
 * only carry `five_hour` and `seven_day`. The reply is then flagged `status:"seeded"`, and `collectUsageData`
 * accepts `"ok"` and `"seeded"` identically, so a seeded refresh is indistinguishable downstream from a full
 * one that genuinely has no per-model window. Verified against `claude` 2.1.223.
 *
 * So the merge is by window key, over the whole set rather than only the per-model ones: `seven_day_opus` and
 * `seven_day_sonnet` are absent from a seeded object for exactly the same reason and would flicker exactly the
 * same way. A carried-forward window keeps the last figure that was actually reported for it, and the next
 * unseeded refresh overwrites it — within one session, which is the only lifetime this holds for.
 *
 * [UsageReport.extra] is deliberately NOT carried: `null` there already means "the plan has no extra-credit
 * balance" as often as it means "this reply did not say", and inventing the distinction would keep a stale
 * balance on screen after the user turns the feature off.
 */
fun UsageReport.mergedOver(previous: UsageReport?): UsageReport {
    val earlier = previous?.windows.orEmpty()
    if (earlier.isEmpty()) return this
    val present = windows.mapTo(mutableSetOf()) { it.first }
    val carried = earlier.filterNot { (key, _) -> key in present }
    return if (carried.isEmpty()) this else copy(windows = sortUsageWindows(windows + carried))
}

private const val MODEL_SCOPED = "model_scoped"

/** The raw per-limit array the binary's own per-model projection reads from, relayed to us untouched. */
private const val RAW_LIMITS = "limits"

/** The `kind` that marks a raw entry as a per-model weekly window. */
private const val WEEKLY_SCOPED = "weekly_scoped"

private fun decodeWindow(obj: JsonObject): UsageWindow? =
    runCatching { ClaudeJson.decodeFromJsonElement(UsageWindow.serializer(), obj) }
        .getOrNull()
        // A window with neither a percentage nor a dollar figure has nothing to draw. Deliberately NOT the
        // same as absent: the binary sends explicit nulls for limits that exist but have not been touched.
        ?.takeIf { it.utilization != null || it.usedDollars != null }

/**
 * The per-model weekly windows, the only ones that name the model they meter — "Fable" is reported here and
 * nowhere else, so without this it simply does not exist for the user.
 *
 * **Two sources, and the second one is the load-bearing one.** The binary offers a ready-made `model_scoped`
 * array, but it emits it only when its own remote config says so: `LCn()` projects the windows through
 * `IUt(limits, jJe())`, where `jJe()` reads the `tengu_usage_overage_included_models` gate and `IUt` returns
 * an EMPTY list the moment that gate is empty — and `rate_limits` then carries no `model_scoped` key at all.
 * Verified against `claude` 2.1.223 (the projection, its gate, and the `i.length > 0` condition that decides
 * whether the key is spliced in), and confirmed live: the plugin's `--print` session logged `five_hour` and
 * `seven_day` and never a per-model window, while the same account's interactive `/usage` listed Fable.
 *
 * So the raw array the projection reads from — `rate_limits.limits[]`, which rides through untouched, as the
 * binary's own `/usage` formatter assumes when it calls `IUt(t.limits, …)` on this very payload — is walked
 * here as well: `kind == "weekly_scoped"` entries with a `scope.model.display_name`, exactly the filter the
 * binary applies, minus its allowlist. Dropping the allowlist is the point: it decides which models get
 * *overage* billing, not which limits a user is subject to, and a limit that meters you is worth showing
 * whether or not you can pay past it.
 *
 * `model_scoped` still wins where both carry a model, since it is the server's own projection; the raw array
 * fills in the rest. Overlap with `seven_day_opus`/`seven_day_sonnet` is expected rather than exceptional
 * (the SDK calls these windows *additive*), so an entry whose label already titles one of [alreadyKeyed] is
 * dropped — two bars reading "Opus" is worse than one, and the keyed window is the one whose meaning the
 * plugin knows. An entry with no name is skipped: its key is synthesised from that name, so a blank one has
 * neither identity nor title and would render as the same unanswerable bar `nimbus_quill` was hidden for.
 */
private fun perModelWindows(
    limits: JsonObject?,
    alreadyKeyed: List<Pair<String, UsageWindow>>,
): List<Pair<String, UsageWindow>> {
    val taken = alreadyKeyed.mapTo(mutableSetOf()) { (key, w) -> w.title(key).lowercase() }
    val candidates = modelScopedEntries(limits) + rawWeeklyScopedEntries(limits)
    return candidates.mapNotNull { (name, window) ->
        if (!taken.add(name.lowercase())) return@mapNotNull null
        "$MODEL_SCOPED_KEY_PREFIX$name" to window
    }
}

/** The binary's own projection, `rate_limits.model_scoped` — present only when its remote gate is set. */
private fun modelScopedEntries(limits: JsonObject?): List<Pair<String, UsageWindow>> {
    val entries = limits?.get(MODEL_SCOPED) as? JsonArray ?: return emptyList()
    return entries.mapNotNull { element ->
        val window = decodeWindow(element as? JsonObject ?: return@mapNotNull null) ?: return@mapNotNull null
        val name = window.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        name to window
    }
}

/**
 * The same windows read from the raw `rate_limits.limits[]` array the binary projects from.
 *
 * Field names differ from every other window here and are taken from the binary, not guessed: the percentage
 * is `percent` (already 0–100 — its `/usage` formatter prints `Math.floor(utilization)%` straight from it) and
 * `resets_at` is epoch **seconds** as often as it is a string, which is why it is normalised rather than
 * handed to the deserializer: a numeric one would fail to decode and silently drop the whole window.
 */
private fun rawWeeklyScopedEntries(limits: JsonObject?): List<Pair<String, UsageWindow>> {
    val entries = limits?.get(RAW_LIMITS) as? JsonArray ?: return emptyList()
    return entries.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        if (obj.str("kind") != WEEKLY_SCOPED) return@mapNotNull null
        val model = (obj["scope"] as? JsonObject)?.get("model") as? JsonObject
        val name = model?.str("display_name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val percent = (obj["percent"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: return@mapNotNull null
        name to UsageWindow(utilization = percent, resetsAt = isoResetsAt(obj["resets_at"]), displayName = name)
    }
}

/** `resets_at` as ISO-8601, converting the epoch-seconds form the raw `limits[]` entries use. */
private fun isoResetsAt(value: kotlinx.serialization.json.JsonElement?): String? {
    val raw = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val epochSeconds = raw.toLongOrNull() ?: return raw
    return runCatching { java.time.Instant.ofEpochSecond(epochSeconds).toString() }.getOrNull()
}

private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()

// ---------------------------------------------------------------------------
// Additional system/* and stream events (E1). Verified against sdk.d.ts
// (SDKTaskProgressMessage, SDKTaskNotificationMessage, SDKTaskStartedMessage,
// SDKTaskUpdatedMessage, SDKToolProgressMessage, SDKToolUseSummaryMessage,
// SDKThinkingTokensMessage, SDKNotificationMessage, SDKPermissionDeniedMessage,
// SDKSessionStateChangedMessage, SDKAuthStatusMessage, SDKAPIRetryMessage,
// SDKCommandsChangedMessage, SDKMemoryRecallMessage, SDKFilesPersistedEvent,
// SDKPromptSuggestionMessage, SDKPluginInstallMessage, SDKHookStartedMessage,
// SDKHookProgressMessage, SDKHookResponseMessage, SDKMirrorErrorMessage).
// All fields optional with defaults so a missing/renamed key never crashes the reader.
// ---------------------------------------------------------------------------

/** Per-subagent token/tool accounting carried by task_progress / task_notification. */
@Serializable
data class TaskUsage(
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("tool_uses") val toolUses: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

/** `system/task_progress` — periodic progress for a running subagent (Task tool). */
@Serializable
data class TaskProgressInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val description: String = "",
    @SerialName("subagent_type") val subagentType: String? = null,
    val usage: TaskUsage = TaskUsage(),
    @SerialName("last_tool_name") val lastToolName: String? = null,
    val summary: String? = null,
    // Mutable lifecycle fields a `task_updated` patch can flip (running → paused/failed/…); surfaced by the UI.
    val status: String? = null, // pending | running | completed | failed | killed | paused
    val error: String? = null,
)

/** `system/task_started` — a subagent task began. */
@Serializable
data class TaskStartedInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val description: String = "",
    @SerialName("subagent_type") val subagentType: String? = null,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("workflow_name") val workflowName: String? = null,
    val prompt: String? = null,
    /** Ambient/housekeeping task — hide from inline transcript (may still show in a tasks panel). */
    @SerialName("skip_transcript") val skipTranscript: Boolean = false,
)

/** `system/task_notification` — a subagent settled (completed/failed/stopped). */
@Serializable
data class TaskNotificationInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val status: String = "", // completed | failed | stopped
    @SerialName("output_file") val outputFile: String = "",
    val summary: String = "",
    val usage: TaskUsage? = null,
    @SerialName("skip_transcript") val skipTranscript: Boolean = false,
)

/** `system/task_updated` — a wire-safe patch of changed TaskState fields; clients merge into their task map. */
@Serializable
data class TaskUpdatedInfo(
    @SerialName("task_id") val taskId: String = "",
    val patch: TaskPatch = TaskPatch(),
)

@Serializable
data class TaskPatch(
    val status: String? = null, // pending | running | completed | failed | killed | paused
    val description: String? = null,
    @SerialName("end_time") val endTime: Long? = null,
    @SerialName("total_paused_ms") val totalPausedMs: Long? = null,
    val error: String? = null,
    @SerialName("is_backgrounded") val isBackgrounded: Boolean? = null,
)

/** `tool_progress` — heartbeat for a long-running tool (top-level type, not system). */
@Serializable
data class ToolProgressInfo(
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("parent_tool_use_id") val parentToolUseId: String? = null,
    @SerialName("elapsed_time_seconds") val elapsedTimeSeconds: Double = 0.0,
    @SerialName("task_id") val taskId: String? = null,
)

/** `tool_use_summary` — a one-line summary that covers several preceding tool_use ids. */
@Serializable
data class ToolUseSummaryInfo(
    val summary: String = "",
    @SerialName("preceding_tool_use_ids") val precedingToolUseIds: List<String> = emptyList(),
)

/** `system/thinking_tokens` — live estimate of reasoning tokens during redacted thinking. */
@Serializable
data class ThinkingTokensInfo(
    @SerialName("estimated_tokens") val estimatedTokens: Int = 0,
    @SerialName("estimated_tokens_delta") val estimatedTokensDelta: Int = 0,
)

/** `system/notification` — loop-side text notification mirroring the REPL queue. */
@Serializable
data class NotificationInfo(
    val key: String = "",
    val text: String = "",
    val priority: String = "low", // low | medium | high | immediate
    val color: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long? = null,
)

/** `system/permission_denied` — a tool call auto-denied without an interactive prompt. */
@Serializable
data class PermissionDeniedInfo(
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("decision_reason_type") val decisionReasonType: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    val message: String = "",
)

/** `system/session_state_changed` — authoritative turn-state signal (idle/running/requires_action). */
@Serializable
data class SessionStateInfo(
    val state: String = "", // idle | running | requires_action
)

/** `auth_status` — top-level type (not system). Auth backend (re)authenticating. */
@Serializable
data class AuthStatusInfo(
    val isAuthenticating: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
)

/** `system/api_retry` — a retryable API failure that will be retried after a delay. */
@Serializable
data class ApiRetryInfo(
    val attempt: Int = 0,
    @SerialName("max_retries") val maxRetries: Int = 0,
    @SerialName("retry_delay_ms") val retryDelayMs: Long = 0,
    @SerialName("error_status") val errorStatus: Int? = null,
    val error: String? = null,
)

/** `system/commands_changed` — full replacement slash-command list pushed mid-session. */
@Serializable
data class CommandsChangedInfo(
    val commands: List<SlashCommand> = emptyList(),
)

/** `system/memory_recall` — memories surfaced into the turn. */
@Serializable
data class MemoryRecallInfo(
    val mode: String = "", // select | synthesize
    val memories: List<RecalledMemory> = emptyList(),
)

@Serializable
data class RecalledMemory(
    val path: String = "",
    val scope: String = "", // personal | team | organization
    val content: String? = null,
)

/** `system/files_persisted` — files uploaded to the Files API (and any that failed). */
@Serializable
data class FilesPersistedInfo(
    val files: List<PersistedFile> = emptyList(),
    val failed: List<FailedFile> = emptyList(),
    @SerialName("processed_at") val processedAt: String = "",
)

@Serializable
data class PersistedFile(
    val filename: String = "",
    @SerialName("file_id") val fileId: String = "",
)

@Serializable
data class FailedFile(
    val filename: String = "",
    val error: String = "",
)

/** `prompt_suggestion` — predicted next user prompt (top-level type, after the result). */
@Serializable
data class PromptSuggestionInfo(
    val suggestion: String = "",
)

/** `system/plugin_install` — headless plugin install progress. */
@Serializable
data class PluginInstallInfo(
    val status: String = "", // started | installed | failed | completed
    val name: String? = null,
    val error: String? = null,
)

/** `system/hook_started` — a hook callback began executing. */
@Serializable
data class HookStartedInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
)

/** `system/hook_progress` — streaming stdout/stderr from a running hook. */
@Serializable
data class HookProgressInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val output: String = "",
)

/** `system/hook_response` — a hook finished (success/error/cancelled). */
@Serializable
data class HookResponseInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
    val output: String = "",
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("exit_code") val exitCode: Int? = null,
    val outcome: String = "", // success | error | cancelled
)

/** `system/mirror_error` — the binary's transcript-mirror batch was dropped after retries (data loss). */
@Serializable
data class MirrorErrorInfo(
    val error: String = "",
    val key: MirrorErrorKey = MirrorErrorKey(),
)

@Serializable
data class MirrorErrorKey(
    val projectKey: String = "",
    val sessionId: String = "",
    val subpath: String? = null,
)

/**
 * `system/model_refusal_fallback` — the primary model ended the stream with stop_reason "refusal" and the
 * turn was retried once on [fallbackModel] (the swap is made persistent for the session; `direction:"retry"`).
 * "revert"/"sticky" are retained in the enum for SDK-consumer compat and are no longer emitted. The refused
 * partial leg is retracted: [retractedMessageUuids] names the wire uuids to evict (idempotent on receipt).
 * [content] is human-readable display prose. [apiRefusalCategory] is an open string ("cyber", "bio", …).
 */
@Serializable
data class ModelRefusalFallbackInfo(
    val trigger: String = "refusal",
    val direction: String = "retry", // retry | revert | sticky (only "retry" is emitted now)
    @SerialName("original_model") val originalModel: String = "",
    @SerialName("fallback_model") val fallbackModel: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("api_refusal_category") val apiRefusalCategory: String? = null,
    @SerialName("api_refusal_explanation") val apiRefusalExplanation: String? = null,
    @SerialName("retracted_message_uuids") val retractedMessageUuids: List<String> = emptyList(),
    val content: String = "",
)

/**
 * `system/informational` (SDK 0.3.193) — a generic text banner from the loop: non-error status lines, hook
 * feedback (e.g. a UserPromptSubmit hook's block reason), slash-command output. [level] drives prominence
 * (info | notice | suggestion | warning). [preventContinuation] means execution stops after this message.
 */
@Serializable
data class InformationalInfo(
    val content: String = "",
    val level: String = "info", // info | notice | suggestion | warning
    @SerialName("tool_use_id") val toolUseId: String? = null,
    @SerialName("prevent_continuation") val preventContinuation: Boolean = false,
)

/**
 * `system/model_refusal_no_fallback` (SDK 0.3.193) — the model ended the stream with stop_reason "refusal" and
 * NO fallback model was configured, so the turn ends as an error. The structured counterpart to detecting a
 * refusal on the assistant error frame. [content] is human-readable display prose.
 */
@Serializable
data class ModelRefusalNoFallbackInfo(
    @SerialName("original_model") val originalModel: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("api_refusal_category") val apiRefusalCategory: String? = null,
    @SerialName("api_refusal_explanation") val apiRefusalExplanation: String? = null,
    @SerialName("refused_user_message_uuid") val refusedUserMessageUuid: String? = null,
    val content: String = "",
)

/**
 * `system/worker_shutting_down` (SDK 0.3.193) — graceful worker teardown with a host-set [reason] (e.g.
 * `host_exit`, `remote_control_disabled`). A LIVE-TAIL signal only: a resumed session may replay historical
 * instances mid-stream, so it's honored as informational and never treated as a session-lifetime fact.
 */
@Serializable
data class WorkerShuttingDownInfo(
    val reason: String = "",
)

/** One live background task as reported by the `system/background_tasks_changed` level signal. */
@Serializable
data class BackgroundTaskInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("task_type") val taskType: String = "",
    val description: String = "",
)

/**
 * `system/background_tasks_changed` (SDK 0.3.204) — the FULL set of live background tasks, re-emitted whenever
 * membership changes (start, completion, kill, a foreground agent being backgrounded).
 *
 * A **level** signal with REPLACE semantics: swap the tracked set for [tasks] on every payload, never pair edges.
 * The SDK is explicit that this must NOT be correlated with the `task_started`/`task_notification` edge stream
 * (ordering between them is unspecified). It is per-process — nothing is emitted at startup — so consumers must
 * reset to the empty set whenever the CLI process (re)starts.
 */
@Serializable
data class BackgroundTasksChangedInfo(
    val tasks: List<BackgroundTaskInfo> = emptyList(),
)

/**
 * `system/control_request_progress` (SDK 0.3.204) — progress for a long-running **host-originated** control
 * request (currently only `side_question`, i.e. `/btw`), correlated by [requestId]. [status] is `started` (the
 * worker accepted the request and launched the work) or `api_retry`, which carries the same retry counters as
 * `system/api_retry` and is present only for that status.
 */
@Serializable
data class ControlRequestProgressInfo(
    @SerialName("request_id") val requestId: String = "",
    val status: String = "", // started | api_retry
    val attempt: Int? = null,
    @SerialName("max_retries") val maxRetries: Int? = null,
    @SerialName("retry_delay_ms") val retryDelayMs: Long? = null,
    @SerialName("error_status") val errorStatus: Int? = null,
)
