package dev.lain.claudejb.session

import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.context.FilePickerHelper

/**
 * The pure half of [ClaudeSession.send]: turns a raw prompt plus its attachments into what the queue actually
 * needs — the wire text sent to the binary, the images, and the text shown in the transcript.
 *
 * The wire text and the display text DIFFER for file attachments: the binary needs an `@<cwd-relative>` mention
 * it actually expands, while the chat shows a clickable `jb://open` link. Building them separately keeps the
 * model's input clean (no markdown link syntax) and the bubble navigable. No IDE, no session, no project — the
 * root is passed in — so this is testable on a plain JVM and cannot itself touch the queue or the process.
 */
object PromptComposer {

    /** What [ClaudeSession.send] needs to queue, or null when there is nothing to send (blank text, no attachments). */
    data class Composed(val wireText: String, val images: List<Pair<String, String>>, val displayText: String)

    /** Assembles [Composed] from [text] and [attachments], relativising file mentions against [projectRoot]. */
    fun compose(text: String, attachments: List<Attachment>, projectRoot: String?): Composed? {
        val nonImage = attachments.filter { it !is Attachment.Image }
        val trimmed = text.trim().takeIf { it.isNotEmpty() }
        val wireParts = buildList {
            trimmed?.let { add(it) }
            nonImage.forEach { add(wireMention(it, projectRoot)) }
        }
        // ONE LINE PER ATTACHMENT, and a blank line only between the prompt and the block of them. They were
        // all joined with `\n\n`, so six attached files were six paragraphs — a wall of them under one line of
        // prose, which is what it looked like. The page renders a user row as markdown with `breaks: true`, so
        // a single newline is a `<br>`: the block reads as a list without becoming one.
        val displayText = buildList {
            trimmed?.let { add(it) }
            nonImage.takeIf { it.isNotEmpty() }?.let { list ->
                add(list.joinToString("\n") { displayMention(it) })
            }
        }.joinToString("\n\n")
        val images = attachments.filterIsInstance<Attachment.Image>().map { it.mediaType to it.base64 }
        val combined = wireParts.joinToString("\n\n")
        if (combined.isEmpty() && images.isEmpty()) return null
        val shown = displayText.ifEmpty { attachments.joinToString(" ") { it.displayName } }
        return Composed(combined, images, shown)
    }

    /** Wire form of a non-image attachment for the binary: a FileRef becomes a `@<cwd-relative>` mention the CLI
     *  expands (absolute `@/…` paths aren't recognized); others fall back to their plain prompt text. */
    private fun wireMention(a: Attachment, root: String?): String = when (a) {
        is Attachment.FileRef -> mentionToken(relativizeForMention(root, a.path))
        else -> a.toPromptText()
    }

    /** An `@path` mention, **quoted** when the path contains whitespace so the CLI's whitespace-delimited mention
     *  parser doesn't truncate it at the first space (e.g. `src/My Notes.md` → `@"src/My Notes.md"`). */
    private fun mentionToken(path: String): String =
        if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

    /** Display form shown in the user bubble: a FileRef becomes a clickable `jb://open` link to the file; others
     *  reuse their prompt text (a selection's fenced snippet, an image marker).
     *
     *  Unlike [wireMention] this does NOT relativise against the project root, and that asymmetry is deliberate:
     *  the model is sent a repo-relative path (portable, and what it should reason about), while the link needs
     *  an ABSOLUTE one to open the file. The visible text is the display name either way, so nothing longer than
     *  a filename is shown. It used to take an unused `root` purely to mirror [wireMention]'s signature. */
    private fun displayMention(a: Attachment): String = when (a) {
        is Attachment.FileRef -> {
            val enc = java.net.URLEncoder.encode(a.path, Charsets.UTF_8).replace("+", "%20")
            "[@${a.displayName}](jb://open?file=$enc&line=1)"
        }

        else -> a.toPromptText()
    }

    /** A project-root-relative path for an `@` mention (forward slashes), or the original path when it's outside
     *  the root or can't be relativized (the CLI won't expand that absolute fallback — a known limitation for
     *  out-of-root attachments). Delegates to the shared [FilePickerHelper.relativeWithinRoot]. */
    private fun relativizeForMention(root: String?, path: String): String =
        FilePickerHelper.relativeWithinRoot(root, path) ?: path
}
