package dev.lain.claudejb.view.jcef

import dev.lain.claudejb.util.logger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal class Page(val html: String, val headers: Map<String, String>)

internal object PageAssembly {

    private val log = logger<PageAssembly>()

    val appNames = listOf(
        "core/core.js",
        "core/menus.js",
        "controllers/boot/report.js",
        "core/markdown.js",
        "core/diagram/diagram.js",
        "core/diagram/view.js",
        "core/theme.js",
        "models/chat/registry.js",
        "views/chat/rows/rows.js",
        "views/chat/rows/guard-notices.js",
        "views/chat/tools/tools.js",
        "views/chat/tools/output.js",
        "views/chat/tools/toon.js",
        "controllers/chat/links.js",
        "models/chat/find.js",
        "views/chat/find-bar.js",
        "views/chat/scroll.js",
        "controllers/chat/trim.js",
        "controllers/chat/row.js",
        "controllers/chat/transcript.js",
        "models/composer/state.js",
        "views/composer/bar/overflow-fit.js",
        "views/composer/bar/overflow.js",
        "controllers/composer/menus.js",
        "views/composer/pills.js",
        "views/composer/attach/glyphs.js",
        "models/composer/attach/tree.js",
        "views/composer/attach/tree-view.js",
        "controllers/composer/attach/tree-select.js",
        "controllers/composer/attach/tree-keys.js",
        "views/composer/attach/menu.js",
        "views/composer/attach/chips.js",
        "controllers/composer/attach/images.js",
        "views/composer/readout/readout.js",
        "views/composer/readout/mini.js",
        "models/composer/palette/rank.js",
        "views/composer/palette/list.js",
        "controllers/composer/palette.js",
        "views/composer/onboarding/boot.js",
        "views/composer/onboarding/auth.js",
        "views/composer/actions.js",
        "models/composer/settings/data.js",
        "views/composer/settings/rows.js",
        "controllers/composer/settings.js",
        "views/composer/flame.js",
        "views/composer/toggles.js",
        "controllers/composer/send.js",
        "controllers/composer/composer.js",
        "views/permissions/base.js",
        "views/permissions/cards/question.js",
        "views/permissions/cards/elicit.js",
        "views/permissions/cards/tool.js",
        "controllers/permissions/permissions.js",
        "views/panel/base.js",
        "views/session/usage.js",
        "views/session/cards.js",
        "views/session/mcp.js",
        "views/workloads/tree.js",
        "views/workloads/workloads.js",
        "views/git/git.js",
        "views/git/actions.js",
        "models/git/lanes.js",
        "views/git/history.js",
        "controllers/git/chat.js",
        "models/guard/state.js",
        "views/guard/filters.js",
        "views/guard/entries.js",
        "views/guard/guard.js",
        "models/vuln/state.js",
        "views/vuln/status.js",
        "views/vuln/findings.js",
        "controllers/vuln/vuln.js",
        "models/log/state.js",
        "views/log/entries.js",
        "controllers/log/log.js",
        "models/panel/state.js",
        "models/panel/reconcile.js",
        "views/panel/render.js",
        "views/panel/toggles.js",
        "controllers/panel/dashboard.js",
        "models/tabs/state.js",
        "models/tabs/guard.js",
        "views/tabs/pill.js",
        "views/tabs/scroll.js",
        "controllers/tabs/tabs.js",
    )

    val CSS_PARTS = listOf(
        "base.css",
        "views/chat/rows.css",
        "views/chat/markdown.css",
        "views/chat/fold.css",
        "views/chat/tools.css",
        "views/chat/output.css",
        "views/chat/code.css",
        "views/chat/notices.css",
        "views/chat/toon.css",
        "views/composer/card.css",
        "views/composer/bar.css",
        "views/composer/god-mode.css",
        "views/composer/attach.css",
        "views/composer/readout.css",
        "views/composer/palette.css",
        "views/permissions/cards.css",
        "views/permissions/elicit.css",
        "views/composer/attachments.css",
        "views/chat/find-bar.css",
        "views/panel/panel.css",
        "views/panel/cards.css",
        "views/panel/diagram.css",
        "views/panel/mini.css",
        "views/git/view.css",
        "views/git/history.css",
        "views/git/chat.css",
        "views/guard/log.css",
        "views/guard/filters.css",
        "views/vuln/status.css",
        "views/vuln/findings.css",
        "views/log/view.css",
        "views/composer/boot-screen.css",
        "views/composer/auth.css",
        "theme/vibe.css",
        "theme/focus.css",
        "views/tabs/bar.css",
        "views/tabs/pills.css",
    )

    private val LIB_NAMES = listOf("purify.min.js", "marked.min.js", "highlight.min.js")

    fun build(): Page {
        val shell = readResource("shell.html")
            ?: return Page(FALLBACK_HTML, headersFor(cspWith("'none'", "'none'")))

        val styleInner = "\n" + CSS_PARTS.joinToString("\n") { readResource("css/$it").orEmpty() } + "\n"
        val styleSrc = "'sha256-" + sha256Base64(styleInner) + "'"

        val contents = LinkedHashMap<String, String>()
        (LIB_NAMES + appNames).forEach { name -> readResource(name)?.let { contents[name] = it } }

        val absent = (LIB_NAMES + appNames).filterNot { contents.containsKey(it) }
        if (absent.isNotEmpty()) {
            log.warn("Claude Code chat page is missing declared scripts, so parts of the UI cannot exist: $absent")
        }

        val hashes = contents.values.map { "'sha256-" + sha256Base64(it) + "'" }
        val scriptSrc = if (hashes.isEmpty()) "'none'" else hashes.joinToString(" ")
        val csp = cspWith(scriptSrc, styleSrc)

        fun block(names: List<String>): String =
            names.filter { contents.containsKey(it) }.joinToString("\n") { "<script>${contents[it]}</script>" }

        val html = shell
            .replace("<!--CSP-->", "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">")
            .replace("<!--CSS-->", "<style>$styleInner</style>")
            .replace("<!--LIBS-->", block(LIB_NAMES))
            .replace("<!--APP-->", block(appNames))

        return Page(html, headersFor(csp))
    }

    private fun readResource(name: String): String? =
        PageAssembly::class.java.getResourceAsStream("/jcef/$name")?.use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }

    private const val PERMISSIONS_POLICY =
        "accelerometer=(), autoplay=(), camera=(), clipboard-read=(), clipboard-write=(), " +
            "display-capture=(), encrypted-media=(), fullscreen=(), geolocation=(), gyroscope=(), " +
            "magnetometer=(), microphone=(), midi=(), payment=(), usb=(), xr-spatial-tracking=()"

    private fun cspWith(scriptSrc: String, styleSrc: String): String =
        "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; " +
            "object-src 'none'; img-src data:; font-src 'none'; connect-src 'none'; " +
            "style-src $styleSrc; script-src $scriptSrc; upgrade-insecure-requests"

    private val BASE_HEADERS = linkedMapOf(
        "Content-Type" to "text/html; charset=utf-8",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Permissions-Policy" to PERMISSIONS_POLICY,
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Embedder-Policy" to "require-corp",
        "Cross-Origin-Resource-Policy" to "same-origin",
        "X-XSS-Protection" to "1; mode=block",
        "Cache-Control" to "no-store, max-age=0",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "Clear-Site-Data" to "\"cookies\", \"storage\"",
    )

    private fun headersFor(csp: String): Map<String, String> =
        LinkedHashMap(BASE_HEADERS).apply { put("Content-Security-Policy", csp) }

    private fun sha256Base64(s: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)),
        )

    private const val FALLBACK_HTML =
        "<!doctype html><html><body style=\"font-family:sans-serif;padding:16px\">" +
            "Claude Code failed to load its UI resources." +
            "</body></html>"
}
