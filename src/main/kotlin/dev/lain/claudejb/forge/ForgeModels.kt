package dev.lain.claudejb.forge

/**
 * A pull request or a merge request, flattened to what a card draws.
 *
 * Deliberately ONE type for both providers, normalised host-side. The alternative — handing the page a
 * provider tag and letting it branch — is how the tab bar came to say `done` where the dashboard said
 * `completed`: two views deriving the same fact from the same data and disagreeing. The page paints these
 * fields and derives nothing.
 *
 * [number] is the number a human sees and quotes: `#42`. On GitLab that is `iid` (per-project), NOT `id`
 * (global) — using `id` shows a five-digit number that matches nothing in the UI and links nowhere.
 *
 * [url] is the provider's own web URL for the request, taken from the payload rather than assembled here:
 * a URL we build by hand is a URL that breaks the day a self-hosted instance is served under a path prefix.
 */
data class ForgePullRequest(
    val number: Long,
    val title: String,
    val url: String,
    val state: String,
    val draft: Boolean,
    val author: String?,
)

/**
 * The state of a CI run, in the ONE vocabulary the page already colours by.
 *
 * The four words are `ui/jcef/JcefStatus`'s — `running` · `completed` · `failed` · `stopped` — and they are
 * reproduced rather than imported because `JcefStatus` is an internal object in the UI layer whose two
 * existing overloads take an `AgentStatus` and a `Boolean`; adding a third for a type it cannot see from
 * here would invert the dependency. What must not happen is a FIFTH word: the page has CSS for these four
 * and paints whatever it is sent, so a new spelling is an uncoloured pill nobody notices.
 */
enum class ForgeRunStatus(val wire: String) {

    /** Queued, waiting, or executing. Both providers have several words for this; they all land here. */
    RUNNING("running"),

    /** Finished and not failing. GitHub's `neutral` counts, because GitHub does not treat it as a failure. */
    COMPLETED("completed"),

    /** Finished and failing, including a timeout and a run that is blocked awaiting approval. */
    FAILED("failed"),

    /** Cancelled or skipped: it will not finish, and nothing is going to finish it. */
    STOPPED("stopped"),
}

/**
 * The LAST CI run of a branch — one indicator, not a job graph.
 *
 * A GitHub Actions workflow run and a GitLab pipeline, flattened onto the same three questions a badge
 * answers: what is it called, how did it go, and when did it stop.
 *
 * [finishedAtIso] is null while [status] is [ForgeRunStatus.RUNNING], and that is a correctness rule rather
 * than a nicety: both providers report the field this is read from (`updated_at`) on a run that is still
 * going, where it means "last touched" and not "finished". Showing it as a finish time would put a
 * completion timestamp on a run that has not completed. It is an ISO-8601 instant exactly as the provider
 * spelled it — this package does not parse dates, because the only consumer is a label and a parse here is a
 * second place for a timezone to be got wrong.
 */
data class ForgeRun(
    val name: String?,
    val status: ForgeRunStatus,
    val url: String,
    val finishedAtIso: String?,
)
