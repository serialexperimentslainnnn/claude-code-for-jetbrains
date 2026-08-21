package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project

internal sealed interface ForgeActionRequest {

    val attempted: String

    val done: String

    fun confirmed(project: Project): Boolean = true

    data class Approve(val number: Long) : ForgeActionRequest {
        override val attempted = "`#$number` was not approved:"
        override val done = "`#$number` is approved."
    }

    data class Unapprove(val number: Long) : ForgeActionRequest {
        override val attempted = "The approval on `#$number` was not withdrawn:"
        override val done = "Your approval on `#$number` is withdrawn."
    }

    data class Merge(val number: Long, val title: String, val target: String?) : ForgeActionRequest {
        override val attempted = "`#$number` was not merged:"
        override val done = "`#$number` is merged."
        override fun confirmed(project: Project): Boolean =
            ForgeActionPrompt.confirmMerge(project, number, title, target)
    }

    data class Comment(val number: Long, val text: String) : ForgeActionRequest {
        override val attempted = "The comment on `#$number` was not posted:"
        override val done = "Your comment is on `#$number`."
    }

    data class Open(val source: String, val target: String, val title: String) : ForgeActionRequest {
        override val attempted = "Nothing was opened from `$source`:"
        override val done = "A request from `$source` into `$target` is open."
        override fun confirmed(project: Project): Boolean =
            ForgeActionPrompt.confirmOpen(project, source, target)
    }

    data class RetryRun(val runId: Long) : ForgeActionRequest {
        override val attempted = "That run was not started again:"
        override val done = "That run is going again."
    }

    data class CancelRun(val runId: Long) : ForgeActionRequest {
        override val attempted = "That run was not cancelled:"
        override val done = "That run is cancelled."
    }
}
