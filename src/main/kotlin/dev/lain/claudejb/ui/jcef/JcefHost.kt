package dev.lain.claudejb.ui.jcef

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
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
 * How the assembled page reaches the browser, in the order the rungs are tried.
 *
 * There is more than one rung because the same document has to arrive in two different places. Locally the CEF
 * scheme handler is the best of them: it costs no socket and still carries real response headers. Under Remote
 * Development the browser runs in the thin client while the scheme is registered in the backend, so the client
 * resolves nothing at all — [LOOPBACK] is the rung that still carries those headers there, reached through the
 * user's own port forward. [INLINE] is `loadHTML`: it always renders, and it is the one rung that loses the
 * headers, which is why it sits below the socket rather than above it. [NOTICE] renders no chat at all — it
 * names the port and the command that opens the forward.
 *
 * Declaration order is the ladder order: a rung is only ever promoted to a strictly later one.
 */
internal enum class PageRoute { SCHEME, LOOPBACK, INLINE, NOTICE }

/**
 * The rung to try after [current] failed to bring the page up, or null when there is none left.
 *
 * [PageRoute.NOTICE] is reachable only when a loopback port actually bound ([loopbackBound]): with no port there
 * is no forward to ask for, and a notice naming a port that does not exist is worse than the blank view it
 * replaces.
 */
internal fun nextPageRoute(current: PageRoute, loopbackBound: Boolean): PageRoute? = when (current) {
    PageRoute.SCHEME -> PageRoute.LOOPBACK
    PageRoute.LOOPBACK -> PageRoute.INLINE
    PageRoute.INLINE -> PageRoute.NOTICE.takeIf { loopbackBound }
    PageRoute.NOTICE -> null
}

/** The first status code that means the server answered with an error instead of the document. */
private const val HTTP_ERROR_FLOOR = 400

/**
 * Whether a finished load actually delivered the document we asked for — the question `onLoadEnd` alone does
 * not answer, because it fires for a failed navigation too (Chromium loads its own error page and reports the
 * load as ended).
 *
 * Both arguments are needed and neither is sufficient. [loadFailed] is the network verdict, reported to
 * `onLoadError` just before the end of the same load: it is the ONLY signal that a rung of the ladder was not
 * reachable at all, which is the Remote Development case — the scheme resolves in the thin client to nothing.
 * [httpStatusCode] is the server's, and it is what catches a rung that answered but not with the page: the
 * loopback server replies 404 to any path that is not the one-shot URL.
 *
 * A status of `0` is NOT a failure. It is what a non-HTTP load reports, and the ladder's own `loadHTML` rungs
 * would be read as broken by a naive `!= 200` — they are served at 200 by the platform's own scheme handler
 * today, and a rule that depends on that is a rule that breaks when the platform changes how `loadHTML` works.
 *
 * The consequence of getting this wrong is not a blank frame: [JcefHost.exec] queues everything the page owes
 * until the load ends, so treating a failed load as a success **drains that queue into a page that cannot run
 * it**, and the next rung comes up with nothing left to draw.
 */
internal fun pageArrived(httpStatusCode: Int, loadFailed: Boolean): Boolean =
    !loadFailed && httpStatusCode >= 0 && httpStatusCode < HTTP_ERROR_FLOOR

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
 *    host API (not subject to the page CSP), queued until a load that actually delivered the page ([pageArrived])
 *    and then flushed in order — a load that failed leaves the queue for the rung that follows it.
 *  - Navigation is cancelled and popups refused; links are routed through the bridge and gated host-side.
 *
 * Delivery is the [PageRoute] ladder rather than a single route: a rung that has not produced a live web app
 * [READY_WATCHDOG_MS] after it was asked for promotes to the next one, and each rung is delivered at most once
 * per browser, so the ladder always terminates. Only [PageRoute.SCHEME] and [PageRoute.LOOPBACK] carry the real
 * headers; [PageRoute.INLINE] keeps the hash-pinned CSP through the page's own meta tag and loses the rest. All
 * ready-flag, route and queue access is confined to the EDT.
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

    /** The assembled page, kept so a later rung of the ladder can serve the very same bytes. */
    private var page: Page? = null

    /** True once the web app has actually announced itself (the `ready` bridge message reached us). EDT-only. */
    private var webReady: Boolean = false

    /** The rung the page is currently being delivered by. Only ever moves forward. EDT-only. */
    private var route: PageRoute? = null

    /** The loopback server backing [PageRoute.LOOPBACK], from the moment it binds until it is stopped. */
    @Volatile
    private var loopback: LoopbackPageServer? = null

    /** True once the parent disposable has fired, so a bind that lands after teardown closes its own socket. */
    @Volatile
    private var disposed: Boolean = false

    /**
     * Whether the load now ending reported an error for the main frame. Written and read on the CEF thread
     * that delivers the load callbacks, so it is volatile rather than EDT-confined — see [installLoadHandler].
     */
    @Volatile
    private var mainFrameLoadFailed: Boolean = false

    /**
     * What advances the ladder. A rung that has not produced a live web app this long after it was asked for has
     * not worked: the scheme handler is process-global and can race the first browser's `loadURL`, leaving a page
     * with no scripts and therefore no `__ccSend`, and under Remote Development the scheme resolves in the client
     * to nothing at all. It is armed when a rung is *delivered* rather than when a load ends, because a rung that
     * never reaches the browser never produces a load-end either. Cancelled as soon as the web app announces ready.
     */
    private val readyWatchdog = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    val component: JComponent

    init {
        if (!supported) {
            browser = null
            jsQuery = null
            component = JBLabel(
                "Claude Code needs JCEF — enable `ide.browser.jcef.enabled` in the Registry and restart.",
            ).apply {
                border = EmptyBorder(16, 16, 16, 16)
            }
        } else {
            val b = JBCefBrowser.createBuilder().build()
            browser = b
            component = b.component

            // The browser must be disposed with the parent; register early so even an early failure cleans up.
            Disposer.register(parentDisposable, b)

            // Whatever socket the ladder opens dies with the parent, even when `dispose()` is never called, and
            // the flag closes the race where a bind completes after teardown has already run.
            Disposer.register(
                parentDisposable,
                Disposable {
                    disposed = true
                    loopback?.stop()
                },
            )

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

            val page = buildPage()
            this.page = page
            deliver(b, page, startRoute(schemeAvailable = registerScheme(page)))
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
     * The web app announced it is alive (the `ready` bridge message). Stops the ladder where it stands and
     * settles the keyboard focus. Called by the panel when it receives `Msg.Ready`. Idempotent.
     */
    fun markWebReady() {
        runOnEdt {
            webReady = true
            readyWatchdog.cancelAllRequests()
            // Everything is inlined, so the page is a single request and its one-shot secret is already spent:
            // the socket has no remaining purpose and an open port that serves nothing is surface for nothing.
            loopback?.stop()
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
        loopback?.stop()
        jsQuery?.let { Disposer.dispose(it) }
        browser?.let { Disposer.dispose(it) }
    }

    // --- internals -------------------------------------------------------------------------------------------

    /**
     * The three load callbacks are one unit: `onLoadStart` clears the verdict, `onLoadError` records it, and
     * `onLoadEnd` reads it. They arrive on the same CEF thread in that order for a given navigation, so the
     * flag needs visibility across threads ([Volatile]) but no lock.
     *
     * The queue is what makes the order matter. A rung that never brought the page up must leave [pending]
     * exactly as it found it, so the next rung inherits it: draining into a page that cannot run the JS spends
     * the state the page needs to exist and hands the promotion an empty queue.
     */
    private fun installLoadHandler(b: JBCefBrowser, query: JBCefJSQuery) {
        b.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    transitionType: CefRequest.TransitionType?,
                ) {
                    if (frame != null && !frame.isMain) return
                    mainFrameLoadFailed = false
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    if (frame != null && !frame.isMain) return
                    mainFrameLoadFailed = true
                }

                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    // Only react to the top frame finishing.
                    if (frame != null && !frame.isMain) return
                    if (!pageArrived(httpStatusCode, mainFrameLoadFailed)) {
                        // Neither the bridge injection nor the drain: this page cannot run either, and the
                        // queue belongs to the rung that comes next. The rung name and the status go in the
                        // log; the URL never does — under LOOPBACK it carries the one-shot token.
                        runOnEdt {
                            log.warn(
                                "Claude Code chat page did not load over ${route ?: "an unknown route"} " +
                                    "(http status $httpStatusCode) — keeping the queued state for the next one",
                            )
                        }
                        return
                    }
                    val inject = "window.__ccSend = function(p){ " + query.inject("p") + " };"
                    executeNow(b, inject)
                    runOnEdt {
                        ready = true
                        // The document ARRIVED, so the rung did its job and the deadline it was under no
                        // longer applies — see [armReadyWatchdog].
                        relaxReadyWatchdog(b)
                        while (pending.isNotEmpty()) {
                            executeNow(b, pending.poll())
                        }
                    }
                }
            },
            b.cefBrowser,
        )
    }

    /**
     * The rung this browser starts on: the best one available, but never below one that has already proven
     * necessary in this IDE run. Without that memory every chat tab in a remote session would pay the watchdog
     * out again before reaching the rung the previous tab already established. EDT-only.
     */
    private fun startRoute(schemeAvailable: Boolean): PageRoute =
        maxOf(if (schemeAvailable) PageRoute.SCHEME else PageRoute.LOOPBACK, provenRoute)

    /**
     * Deliver the page over [next] and arm the watchdog that will promote past it. Called once per rung, from
     * the constructor and from [promote] — which only ever moves forward — so no rung is delivered twice and
     * the ladder cannot loop. EDT-only.
     */
    private fun deliver(b: JBCefBrowser, page: Page, next: PageRoute) {
        route = next
        // The notice is terminal: it carries no script, so it can never announce ready and must not be waited on.
        if (next != PageRoute.NOTICE) armReadyWatchdog(b)
        when (next) {
            PageRoute.SCHEME -> b.loadURL(PAGE_URL)
            PageRoute.LOOPBACK -> serveOverLoopback(b, page)
            PageRoute.INLINE -> b.loadHTML(page.html)
            PageRoute.NOTICE -> loopback?.let { b.loadHTML(RemoteDevNotice.html(it.port)) }
        }
    }

    /**
     * Arms the promotion shot for the rung just delivered — the DELIVERY deadline. EDT-only.
     *
     * This clock is about the transport and nothing else: if the document has not arrived by now, this rung
     * cannot deliver it and the next one is worth trying.
     */
    private fun armReadyWatchdog(b: JBCefBrowser) {
        if (webReady) return
        readyWatchdog.cancelAllRequests()
        readyWatchdog.addRequest({ if (!webReady) promote(b) }, READY_WATCHDOG_MS)
    }

    /**
     * The document arrived: re-arm the promotion far out, because what is left to wait for is not the rung.
     *
     * **This is the fix for "opening a chat reloads the whole plugin".** The ladder used to promote on a flat
     * timer that knew nothing about whether the page had loaded, so a document that ARRIVED and was merely
     * slow to run its scripts — a cold CEF browser parsing thirty-odd hash-pinned files, which is every new
     * chat on a busy machine — got the whole page re-delivered over the next transport. The user watched the
     * entire UI build itself twice, behind a boot screen that stayed up across both, and nothing was actually
     * wrong. Delivering it again over a different socket cannot make scripts run faster; it restarts them.
     *
     * Not cancelled outright, and that is the one thing this must not do. There is a real failure that looks
     * exactly like a slow page: the document loads while its SCRIPTS are blocked — a proxy or a policy
     * rewriting the CSP header, say — and the rung that recovers from it is [PageRoute.INLINE], which carries
     * the policy in the document's own meta tag instead. So arrival converts a delivery deadline into a much
     * longer "these scripts are never going to run" one, which is a different claim and deserves its own
     * patience. EDT-only.
     */
    private fun relaxReadyWatchdog(b: JBCefBrowser) {
        if (webReady) return
        readyWatchdog.cancelAllRequests()
        readyWatchdog.addRequest({ if (!webReady) promote(b) }, SCRIPTS_WATCHDOG_MS)
    }

    /**
     * Move to the next rung, or stop when there is none. Every outcome is logged, including the last one: a
     * silent ladder is indistinguishable from a feature nobody wired up, which is the failure this exists to
     * end. The port and the page bytes stay out of the log — only the rung names go in. EDT-only.
     */
    private fun promote(b: JBCefBrowser) {
        val current = route ?: return
        val assembled = page ?: return
        val next = nextPageRoute(current, loopbackBound = loopback != null)
        if (next == null) {
            log.warn("Claude Code chat did not come up over $current and there is no route left to try")
            return
        }
        ready = false
        log.warn("Claude Code chat did not come up over $current — delivering it over $next instead")
        remember(next)
        deliver(b, assembled, next)
    }

    /**
     * Records a rung as proven for the rest of the IDE run, so later browsers start there.
     *
     * Only [PageRoute.LOOPBACK] is worth remembering, and the cap is deliberate: the rungs past it describe a
     * forward the user has not opened yet, and remembering one would stop every later tab from trying the socket
     * again once they do open it — turning a temporary condition into a permanent one.
     */
    private fun remember(reached: PageRoute) {
        if (reached == PageRoute.LOOPBACK) provenRoute = PageRoute.LOOPBACK
    }

    /**
     * Bind the loopback rung off the EDT and, back on it, point the browser at the URL the bind produced. A bind
     * that lands after teardown, or after the watchdog has already moved on, closes its own socket instead of
     * leaking it.
     */
    private fun serveOverLoopback(b: JBCefBrowser, page: Page) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val server = LoopbackPageServer.start(page.html, page.headers)
            runOnEdt {
                if (disposed || route != PageRoute.LOOPBACK) {
                    server?.stop()
                    return@runOnEdt
                }
                if (server == null) {
                    log.warn("Claude Code could not bind a loopback port for the chat page")
                    promote(b)
                } else {
                    loopback = server
                    b.loadURL(server.url)
                }
            }
        }
    }

    private fun installNavigationGuards(b: JBCefBrowser) {
        // Cancel any attempt to navigate the top-level frame: all links are routed through the bridge.
        b.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
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
            },
            b.cefBrowser,
        )

        // Never spawn popups / external browser windows from the view.
        b.jbCefClient.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    targetUrl: String?,
                    targetFrameName: String?,
                ): Boolean = true
            },
            b.cefBrowser,
        )
    }

    /**
     * Runs [js] in the page — wrapped, because an exception in here is otherwise **completely silent**.
     *
     * `executeJavaScript` is a fire-and-forget evaluation: it is not a `<script>` element, so a throw inside
     * it fires no `error` event on `window`, reaches no handler the page installed, and lands in a CEF
     * console nothing reads. Every host→page call goes through here, so what that silence hides is any
     * feature whose push dies halfway — and the symptom is not an error, it is a part of the UI that simply
     * is not there. That is exactly how a tab bar went missing for an afternoon: the host pushed the chats,
     * the page threw while drawing them, and every log, every test and every screenshot agreed that nothing
     * had happened.
     *
     * The catch reports through the bridge the page already has, so it arrives in `idea.log` as a WARN
     * (`ChatBridgeRouter`). It is guarded on `__ccSend` existing: before the bridge is injected there is
     * nowhere to send, and a throw inside the catch would be worse than the one it is reporting.
     */
    private fun executeNow(b: JBCefBrowser, js: String) {
        val url = b.cefBrowser.url ?: PAGE_URL
        val guarded = "try{" + js + "}catch(e){try{window.__ccSend&&window.__ccSend(JSON.stringify(" +
            "{type:'diag',report:'uncaught exec: '+((e&&e.stack)||e)}))}catch(_){}}"
        b.cefBrowser.executeJavaScript(guarded, url, 0)
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
        //
        // The stylesheet is SEVERAL files, concatenated IN THIS ORDER. It was one 3.6k-line file; the parts
        // are the sections it already had. Order is not cosmetic here — later rules win — so the list is the
        // contract, and a new part goes where its rules belong, not at the end for convenience.
        val styleInner = "\n" + CSS_PARTS.joinToString("\n") { readResource("css/$it").orEmpty() } + "\n"
        val cssBlock = "<style>$styleInner</style>"
        val styleSrc = "'sha256-" + sha256Base64(styleInner) + "'"

        val libNames = listOf("purify.min.js", "marked.min.js", "highlight.min.js")

        // The app, in LOAD ORDER — which is a contract, not a listing. There is no module system in the page:
        // each file is its own hash-pinned <script> and they meet through `window.cc` / `window.CC`. So a file
        // that reads another's namespace at load time must come after it. Two rules carry the whole list:
        //   - `app-core.js` first: it creates `cc`/`CC` and everything else extends them.
        //   - each family's shared namespace before its members: `app-transcript.js` creates `CC.transcript`
        //     and so leads its own family, while the composer, the dashboard and the tab bar keep theirs in a
        //     `-base.js` and put their SPINE last. For `app-composer.js` and `app-session.js` that last place
        //     is load-bearing rather than tidy — both build their UI eagerly on load, so every collaborator
        //     they call has to exist by then. `app-tabs.js` builds nothing at load, but it owns `render` and
        //     the Kotlin-facing methods and reaches for all five companions, so it is listed the same way.
        val appNames = listOf(
            "app-core.js",
            "app-core-markdown.js",
            "app-core-diagram.js",
            "app-core-theme.js",
            "app-transcript.js",
            "app-transcript-rows.js",
            "app-transcript-tools.js",
            "app-transcript-links.js",
            "app-transcript-find.js",
            "app-composer-base.js",
            "app-composer-menus.js",
            "app-composer-pills.js",
            "app-composer-attach.js",
            "app-composer-readout.js",
            "app-composer-palette.js",
            "app-composer-boot.js",
            "app-composer-auth.js",
            "app-composer-actions.js",
            "app-composer-settings.js",
            "app-composer.js",
            "app-permissions.js",
            "app-session-base.js",
            "app-session-cards.js",
            "app-session-mcp.js",
            "app-session-workloads.js",
            "app-session-git.js",
            // After `app-session-git.js` (it heads itself with that file's strip builder) and after
            // `app-permissions.js` (it draws its cards with that file's renderer), and before the spine,
            // which asks it for the pane on its first visibility pass.
            "app-session-gitchat.js",
            "app-session.js",
            "app-tabs-base.js",
            "app-tabs-guard.js",
            "app-tabs-tree.js",
            "app-tabs-pill.js",
            "app-tabs-scroll.js",
            "app-tabs.js",
        )

        // Read each script once; the hash must be of the EXACT text between <script> and </script>.
        val contents = LinkedHashMap<String, String>()
        (libNames + appNames).forEach { name -> readResource(name)?.let { contents[name] = it } }

        // **A declared module that does not resolve is a feature that silently is not there.** The page is a
        // pile of independent <script> tags, so a missing one leaves the rest working: `window.cc.tabs` is
        // never defined, the host's own `cc.tabs && cc.tabs(...)` guard skips, and the user sees a tool
        // window with no tab bar and no error anywhere. Skipping it is still the right behaviour — half a
        // page beats none — but doing it QUIETLY is not, and this was quiet for as long as it existed.
        val absent = (libNames + appNames).filterNot { contents.containsKey(it) }
        if (absent.isNotEmpty()) {
            log.error("Claude Code chat page is missing declared scripts, so parts of the UI cannot exist: $absent")
        }

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

        /** The stylesheet, in cascade order — see [buildPage]. */
        private val CSS_PARTS = listOf(
            "base.css",
            "transcript.css",
            "composer.css",
            "permissions.css",
            "dashboard.css",
            // After the dashboard, never before it: the Git view is a dashboard view, so its rules extend
            // `.dash-card` and friends and must be able to win over them at equal specificity.
            "git.css",
            "boot.css",
            "tabs.css",
        )

        /** How long a rung gets to produce a live web app before the ladder promotes past it. */
        private const val READY_WATCHDOG_MS = 2500

        /**
         * How long a page that ARRIVED gets to run its scripts before the ladder gives up on it.
         *
         * Long, deliberately. What it is waiting for is a cold CEF browser parsing and running thirty-odd
         * hash-pinned files, and the cost of being wrong is not a slow tab — it is re-delivering the whole
         * document over another transport, which the user sees as the entire UI building itself twice with
         * the boot screen up across both. The only thing on the other side of this deadline is a page whose
         * scripts are BLOCKED rather than slow, which is rare and does not get less rare by being caught
         * sooner.
         */
        private const val SCRIPTS_WATCHDOG_MS = 20_000

        /**
         * The lowest rung any browser may start on, for the rest of this IDE run. Application-wide on purpose:
         * the condition it records — a scheme handler the browser cannot reach — belongs to the IDE, not to one
         * chat tab, so the second tab of a remote session must not pay the watchdog out again to learn it.
         */
        @Volatile
        private var provenRoute: PageRoute = PageRoute.SCHEME

        /** Every response our scheme handler serves comes from memory and always succeeds — hence a fixed 200. */
        private const val HTTP_OK = 200

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

        private const val FALLBACK_HTML =
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
            response.status = HTTP_OK
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
