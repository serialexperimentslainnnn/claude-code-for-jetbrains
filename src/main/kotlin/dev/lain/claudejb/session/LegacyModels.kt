package dev.lain.claudejb.session

/**
 * Previous-generation models, offered under "Other models" in the model picker.
 *
 * **Why this list exists in the plugin at all, which is the uncomfortable part.** The binary does not offer
 * them: its selectable catalog — the one that arrives in the `initialize` reply, and the identical one behind
 * the `list_models` control request — contains only the current generation (verified against `claude` 2.1.223:
 * `default`, `opus[1m]`, `claude-fable-5[1m]`, `sonnet`, `haiku`, and nothing else). `ModelInfo` carries no
 * `deprecated` or `legacy` flag either, so there is no runtime source to derive this from. It still ACCEPTS
 * these ids on `--model` and `set_model` — they remain in its own model tables — it simply will not list them.
 *
 * So a curated list is the only way to put an older model in front of the user, and it is deliberately a list
 * of **historical** ids. That distinction is what makes it maintainable: a released model id never changes and
 * never disappears, so this file can only ever gain entries — unlike the hardcoded `"Default · Opus 4.8"`
 * label removed in 4.3.3, which described the *current* tier and was wrong the moment the recommendation
 * moved. Nothing here names the current generation, and nothing here is used as a default.
 *
 * Sourced from the ids the shipped binary itself still names, and verifiable in one command rather than from
 * memory — `grep -ahoE "claude-(opus|sonnet|haiku)-[0-9][a-z0-9-]*" "$(readlink -f "$(which claude)")" | sort -u`
 * over `claude` 2.1.223. Worth doing before adding an entry: an id invented from the version-numbering pattern
 * looks right and is simply refused at `set_model` (there is no `claude-opus-4-2`, nor a `claude-sonnet-4-2`).
 * Labels are written out rather than derived:
 * [dev.lain.claudejb.ui.jcef.JcefState.deriveModelLabel] renders `claude-opus-4-7` correctly but turns
 * `claude-3-5-sonnet` into "3 5 Sonnet", because the version leads the family in the 3.x naming scheme.
 *
 * An entry the account cannot use is not filtered here — we cannot know that without asking, and asking costs
 * a turn. Selecting one that the plan refuses is handled where the refusal actually arrives: the session
 * reverts to the previous model and says so (see [ClaudeSession.changeModel]).
 */
object LegacyModels {

    /** One selectable older model: the id sent to the binary, and how it is shown. */
    data class Entry(val value: String, val label: String)

    /**
     * Newest first, grouped by family — the order they are shown in. Opus before Sonnet before Haiku, which
     * is the order of the current catalog, so the submenu reads like the menu above it.
     */
    val ALL: List<Entry> = listOf(
        Entry("claude-opus-4-8", "Opus 4.8"),
        Entry("claude-opus-4-7", "Opus 4.7"),
        Entry("claude-opus-4-6", "Opus 4.6"),
        Entry("claude-opus-4-5", "Opus 4.5"),
        Entry("claude-opus-4-1", "Opus 4.1"),
        Entry("claude-opus-4-0", "Opus 4"),
        Entry("claude-sonnet-4-6", "Sonnet 4.6"),
        Entry("claude-sonnet-4-5", "Sonnet 4.5"),
        Entry("claude-sonnet-4-0", "Sonnet 4"),
        Entry("claude-3-7-sonnet", "Sonnet 3.7"),
        Entry("claude-3-5-sonnet", "Sonnet 3.5"),
        Entry("claude-3-5-haiku", "Haiku 3.5"),
    )

    /** The label for [value], or null when it is not one of ours — so callers can fall back to their own rule. */
    fun labelFor(value: String?): String? = value?.let { id -> ALL.firstOrNull { it.value == id }?.label }

    /**
     * The entries worth offering given what the binary already lists, so a model can never appear twice.
     *
     * Matched on the id the CLI would resolve to as well as the row's own value: the catalog offers `sonnet`
     * and `opus[1m]` as aliases, and a future catalog that starts listing a concrete older id must not end up
     * rendering it in both places.
     */
    fun offeredAlongside(catalog: Collection<String>): List<Entry> =
        ALL.filterNot { entry -> catalog.any { it == entry.value } }
}
