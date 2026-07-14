package dev.lain.claudejb.ui.jcef

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.Alarm
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedList
import javax.swing.JComponent
import javax.swing.border.EmptyBorder

/**
 * Thin wrapper over [JBCefBrowser] that hosts the JCEF chat frontend and provides the async JS bridge.
 *
 * Security model (zero-trust — the rendered content is untrusted: model output, MCP-server text, tool output
 * and file contents all flow into the view):
 *  - The page is served from a synthetic, network-less origin (`*.localhost`, a secure context) via a
 *    [CefSchemeHandlerFactory], so the response carries a full set of REAL security headers — not just a CSP
 *    meta tag. See [BASE_HEADERS] + [cspWith].
 *  - The CSP **hash-pins** our exact inline scripts (`'sha256-…'`) and allows NO `'unsafe-inline'`/`'unsafe-eval'`.
 *    So even if a sanitizer bypass injected a `<script>`, an `onclick=` handler or a `javascript:` URL, Chromium
 *    refuses to execute it — injected content can render as inert text but can never run code, read storage, or
 *    reach the `window.__ccSend` bridge. (`marked`/`DOMPurify` remain the first sanitization layer.)
 *  - `default-src 'none'` + `connect-src 'none'` + `img-src data:` → the page cannot fetch/XHR/WebSocket or load
 *    any remote resource, so nothing can be exfiltrated and there is no CORS surface (we emit no
 *    `Access-Control-Allow-Origin`). `Clear-Site-Data` wipes cookies/storage each load; we set neither anyway.
 *  - JS→Kotlin: a [JBCefJSQuery] forwards raw JSON to [onMessage] on the EDT. Kotlin→JS: [exec] runs JS via the
 *    host API (not subject to the page CSP), queued until load-end then flushed in order.
 *  - Navigation is cancelled and popups refused; links are routed through the bridge and gated host-side.
 *
 * If scheme registration is somehow unavailable we fall back to `loadHTML`, where the same hash-pinned CSP still
 * applies via the page's own meta tag. All ready-flag and queue access is confined to the EDT.
 */
class JcefHost(
    parentDisposable: Disposable,
    private val onMessage: (String) -> Unit,
) {

    val supported: Boolean = JBCefApp.isSupported()

    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?

    /** Set true once the page has finished loading and `window.__ccSend` has been injected. EDT-only. */
    private var ready: Boolean = false

    /** JS strings queued before the page was ready. EDT-only. */
    private val pending = LinkedList<String>()

    /** The assembled page, kept so the first-open self-heal can reload it via `loadHTML`. */
    private var page: Page? = null

    /** True once the web app has actually announced itself (the `ready` bridge message reached us). EDT-only. */
    private var webReady: Boolean = false

    /** One-shot guard so the self-heal reload can't loop. EDT-only. */
    private var reloadedOnce: Boolean = false

    /**
     * First-open self-heal: if the very first scheme-served load comes up blank (the process-global scheme
     * handler can race the first browser's `loadURL`, yielding a page with no scripts → no `__ccSend` → a dead
     * chat that only a manual tab-reopen fixed), this watchdog reloads the page via `loadHTML` (independent of
     * the scheme) so the chat heals itself. Cancelled as soon as the web app announces ready.
     */
    private val readyWatchdog = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    val component: JComponent

    init {
        if (!supported) {
            browser = null
            jsQuery = null
            component = JBLabel(
                "Claude Code needs JCEF — enable `ide.browser.jcef.enabled` in the Registry and restart."
            ).apply {
                border = EmptyBorder(16, 16, 16, 16)
            }
        } else {
            val b = JBCefBrowser.createBuilder().build()
            browser = b
            component = b.component

            // The browser must be disposed with the parent; register early so even an early failure cleans up.
            Disposer.register(parentDisposable, b)

            // Select the non-deprecated create(JBCefBrowserBase) overload via a typed local — assigning to `base`
            // (rather than an `as` cast on `b`) avoids smart-narrowing `b`, which stays a JBCefBrowser below.
            val base: JBCefBrowserBase = b
            val query = JBCefJSQuery.create(base)
            jsQuery = query
            Disposer.register(parentDisposable, query)
            query.addHandler { request ->
                ApplicationManager.getApplication().invokeLater { onMessage(request) }
                null
            }

            installNavigationGuards(b)
            installLoadHandler(b, query)

            // Prefer serving from the secure-context origin (real HTTP security headers); fall back to a raw
            // data-page load (the hash-pinned CSP still applies via the page meta tag) if registration fails.
            val page = buildPage()
            this.page = page
            if (registerScheme(page)) b.loadURL(PAGE_URL) else b.loadHTML(page.html)
        }
    }

    /**
     * Run [js] in the page. If the page is ready it executes immediately; otherwise it is queued and flushed,
     * in order, once load-end fires. Always async and EDT-confined; never blocks.
     */
    fun exec(js: String) {
        val b = browser ?: return
        runOnEdt {
            if (ready) {
                executeNow(b, js)
            } else {
                pending.add(js)
            }
        }
    }

    /**
     * The web app announced it is alive (the `ready` bridge message). Cancels the first-open self-heal watchdog,
     * and settles the keyboard focus. Called by the panel when it receives `Msg.Ready`. Idempotent.
     */
    fun markWebReady() {
        runOnEdt {
            webReady = true
            readyWatchdog.cancelAllRequests()
            // THE focus fix (see requestFocus): CEF is told it has the focus only now, once the page it must paint
            // the caret in actually exists.
            if (inputComponent()?.isFocusOwner == true) grantCefFocus()
        }
    }

    /**
     * Give the chat the keyboard focus, the IntelliJ way: [IdeFocusManager] arbitrates focus in the IDE, and a raw
     * `Component.requestFocusInWindow()` issued while it is settling its own is simply dropped.
     *
     * The AWT focus is only half of it. CEF keeps its OWN focus flag, and a freshly loaded page starts with it
     * cleared — so a tab whose browser owns the focus *before* its page has loaded (a tab opened while the IDE is
     * running: the `ContentManager` hands the focus over on selection, ~500ms before the page is up) ends up
     * taking keystrokes with no caret to show for it. Hence [grantCefFocus] is (re)applied from [markWebReady],
     * when the chat is actually there — not here, where the page may not exist yet.
     */
    fun requestFocus() {
        runOnEdt {
            val target = inputComponent() ?: return@runOnEdt
            if (target.isFocusOwner) grantCefFocus() else IdeFocusManager.getGlobalInstance().requestFocus(target, true)
        }
    }

    /** Tell CEF it has the focus and put the caret in the composer. EDT-only; safe before the page is up. */
    private fun grantCefFocus() {
        runCatching { browser?.cefBrowser?.setFocus(true) }
        exec("window.cc.focusInput && window.cc.focusInput()")
    }

    /**
     * The component that actually receives keystrokes — **not** [JBCefBrowser.getComponent], which is a wrapper
     * panel and is not focusable. Null until the native browser has been created, so callers must resolve it
     * lazily rather than capture it once.
     */
    fun inputComponent(): JComponent? {
        val b = browser ?: return null
        return runCatching { b.cefBrowser.uiComponent }.getOrNull() as? JComponent
    }

    /** Optional eager teardown; both the browser and the JS query are also registered with the parent disposable,
     *  and [Disposer.dispose] is a no-op on an already-disposed object, so calling this twice is safe. */
    fun dispose() {
        jsQuery?.let { Disposer.dispose(it) }
        browser?.let { Disposer.dispose(it) }
    }

    // --- internals -------------------------------------------------------------------------------------------

    private fun installLoadHandler(b: JBCefBrowser, query: JBCefJSQuery) {
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                // Only react to the top frame finishing.
                if (frame != null && !frame.isMain) return
                val inject = "window.__ccSend = function(p){ " + query.inject("p") + " };"
                executeNow(b, inject)
                runOnEdt {
                    ready = true
                    while (pending.isNotEmpty()) {
                        executeNow(b, pending.poll())
                    }
                    armReadyWatchdog(b)
                }
            }
        }, b.cefBrowser)
    }

    /**
     * Arms the first-open self-heal: if the web app hasn't announced ready a short while after load-end, the
     * scheme-served page likely came up blank — reload it once via `loadHTML` (scheme-independent) so the chat
     * heals itself instead of staying dead until the user reopens the tab. EDT-only.
     */
    private fun armReadyWatchdog(b: JBCefBrowser) {
        if (webReady || reloadedOnce) return
        readyWatchdog.cancelAllRequests()
        readyWatchdog.addRequest({
            if (!webReady && !reloadedOnce) {
                reloadedOnce = true
                ready = false
                log.warn("JCEF chat did not become ready after load — reloading via loadHTML (first-open self-heal)")
                page?.let { b.loadHTML(it.html) }
            }
        }, READY_WATCHDOG_MS)
    }

    private fun installNavigationGuards(b: JBCefBrowser) {
        // Cancel any attempt to navigate the top-level frame: all links are routed through the bridge.
        b.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                userGesture: Boolean,
                isRedirect: Boolean,
            ): Boolean {
                // Returning true cancels the navigation. The initial programmatic load has no user gesture and is
                // allowed; anything the user could trigger is cancelled (links go through the bridge instead).
                return userGesture
            }
        }, b.cefBrowser)

        // Never spawn popups / external browser windows from the view.
        b.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(
                cefBrowser: CefBrowser?,
                frame: CefFrame?,
                targetUrl: String?,
                targetFrameName: String?,
            ): Boolean = true
        }, b.cefBrowser)
    }

    private fun executeNow(b: JBCefBrowser, js: String) {
        val url = b.cefBrowser.url ?: PAGE_URL
        b.cefBrowser.executeJavaScript(js, url, 0)
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block)
    }

    /**
     * Assemble the full HTML page from `shell.html`, inlining CSS, the vendored libs and the app scripts, and
     * compute the hash-pinned CSP from the exact bytes of each inline script. Returns the page plus the response
     * headers (CSP included) to serve it with. A missing resource is skipped gracefully rather than throwing.
     */
    private fun buildPage(): Page {
        val shell = readResource("shell.html")
            ?: return Page(FALLBACK_HTML, headersFor(cspWith("'none'", "'none'")))

        // The <style> block is hash-pinned just like the scripts, so the CSP needs no `style-src 'unsafe-inline'`.
        // The hash must be of the EXACT text between <style> and </style>.
        val styleInner = "\n${readResource("app.css").orEmpty()}\n"
        val cssBlock = "<style>$styleInner</style>"
        val styleSrc = "'sha256-" + sha256Base64(styleInner) + "'"

        val libNames = listOf("purify.min.js", "marked.min.js", "highlight.min.js")
        val appNames = listOf("app-core.js", "app-transcript.js", "app-composer.js", "app-permissions.js", "app-session.js")

        // Read each script once; the hash must be of the EXACT text between <script> and </script>.
        val contents = LinkedHashMap<String, String>()
        (libNames + appNames).forEach { name -> readResource(name)?.let { contents[name] = it } }

        val hashes = contents.values.map { "'sha256-" + sha256Base64(it) + "'" }
        val scriptSrc = if (hashes.isEmpty()) "'none'" else hashes.joinToString(" ")
        val csp = cspWith(scriptSrc, styleSrc)

        fun block(names: List<String>): String =
            names.filter { contents.containsKey(it) }.joinToString("\n") { "<script>${contents[it]}</script>" }

        val html = shell
            .replace("<!--CSP-->", "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">")
            .replace("<!--CSS-->", cssBlock)
            .replace("<!--LIBS-->", block(libNames))
            .replace("<!--APP-->", block(appNames))

        return Page(html, headersFor(csp))
    }

    private fun readResource(name: String): String? {
        return JcefHost::class.java.getResourceAsStream("/jcef/$name")?.use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    /** The assembled page plus the response headers (CSP included) it must be served with. */
    private class Page(val html: String, val headers: Map<String, String>)

    private companion object {
        private val log = logger<JcefHost>()

        /** How long to wait after load-end for the web app's `ready` before the self-heal reload kicks in. */
        private const val READY_WATCHDOG_MS = 2500

        // A synthetic, network-less origin under the reserved `.localhost` namespace. Chromium treats
        // `*.localhost` as a potentially-trustworthy (secure) context, so the cross-origin-isolation and
        // Clear-Site-Data headers actually take effect — yet our scheme handler intercepts every request, so no
        // DNS, no socket and no real loopback server is ever involved. The host is unique to us, so it cannot
        // collide with a genuine localhost service (e.g. the MCP server) another part of the IDE might run.
        private const val SCHEME = "http"
        private const val DOMAIN = "claude-code.localhost"
        private const val PAGE_URL = "http://claude-code.localhost/index.html"

        /** Disable every powerful browser feature — the chat UI needs none of them. */
        private const val PERMISSIONS_POLICY =
            "accelerometer=(), autoplay=(), camera=(), clipboard-read=(), clipboard-write=(), " +
                "display-capture=(), encrypted-media=(), fullscreen=(), geolocation=(), gyroscope=(), " +
                "magnetometer=(), microphone=(), midi=(), payment=(), usb=(), xr-spatial-tracking=()"

        /**
         * Build the CSP for a given `script-src`. Scripts are allowed only by exact sha256 hash (no
         * `'unsafe-inline'`/`'unsafe-eval'`). `connect-src 'none'` forbids all fetch/XHR/WS (so there is no CORS
         * surface — we never emit `Access-Control-Allow-Origin`). `upgrade-insecure-requests` is belt-and-
         * suspenders: there are no http subresources to upgrade, but it guarantees none could ever sneak in.
         * Deliberately omitted: HSTS (honoured only over real TLS, which this network-less origin never uses).
         */
        private fun cspWith(scriptSrc: String, styleSrc: String): String =
            "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; " +
                "object-src 'none'; img-src data:; font-src 'none'; connect-src 'none'; " +
                "style-src $styleSrc; script-src $scriptSrc; upgrade-insecure-requests"

        /** Real HTTP security headers served with the page (CSP added per-page by [headersFor]). */
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
            // Wipe any cookies/storage for this origin on every load (we set neither, so this just enforces it).
            "Clear-Site-Data" to "\"cookies\", \"storage\"",
        )

        private fun headersFor(csp: String): Map<String, String> =
            LinkedHashMap(BASE_HEADERS).apply { put("Content-Security-Policy", csp) }

        private fun sha256Base64(s: String): String =
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)),
            )

        @Volatile
        private var schemeRegistered = false

        private val FALLBACK_HTML =
            "<!doctype html><html><body style=\"font-family:sans-serif;padding:16px\">" +
                "Claude Code failed to load its UI resources." +
                "</body></html>"

        /**
         * Register the in-memory scheme handler once for the whole CEF app. The page bytes are static (all
         * assets are inlined), so the first registration's [page] serves every browser. Returns true if the
         * secure-header origin is available; false tells the caller to fall back to a plain data-page load.
         */
        @Synchronized
        private fun registerScheme(page: Page): Boolean {
            if (schemeRegistered) return true
            schemeRegistered = runCatching {
                CefApp.getInstance().registerSchemeHandlerFactory(SCHEME, DOMAIN, SecurePageFactory(page))
            }.getOrDefault(false)
            return schemeRegistered
        }
    }

    /** Serves the single inlined HTML document (with the full security-header set) for every request. */
    private class SecurePageFactory(page: Page) : CefSchemeHandlerFactory {
        private val bytes: ByteArray = page.html.toByteArray(StandardCharsets.UTF_8)
        private val headers: Map<String, String> = page.headers
        override fun create(
            browser: CefBrowser?,
            frame: CefFrame?,
            schemeName: String?,
            request: CefRequest?,
        ): CefResourceHandler = SecurePageHandler(bytes, headers)
    }

    /** Streams the page bytes back with the full security-header set. One instance per request. */
    private class SecurePageHandler(
        private val bytes: ByteArray,
        private val headers: Map<String, String>,
    ) : CefResourceHandlerAdapter() {
        private var offset = 0

        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            callback?.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
            response ?: return
            response.status = 200
            response.mimeType = "text/html"
            headers.forEach { (name, value) -> response.setHeaderByName(name, value, true) }
            responseLength?.set(bytes.size)
        }

        override fun readResponse(
            dataOut: ByteArray,
            bytesToRead: Int,
            bytesRead: IntRef,
            callback: CefCallback?,
        ): Boolean {
            if (offset >= bytes.size) {
                bytesRead.set(0)
                return false
            }
            val n = minOf(bytesToRead, bytes.size - offset)
            System.arraycopy(bytes, offset, dataOut, 0, n)
            offset += n
            bytesRead.set(n)
            return true
        }
    }
}
