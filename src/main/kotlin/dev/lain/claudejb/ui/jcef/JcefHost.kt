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

internal enum class PageRoute { SCHEME, LOOPBACK, INLINE, NOTICE }

internal fun nextPageRoute(current: PageRoute, loopbackBound: Boolean): PageRoute? = when (current) {
    PageRoute.SCHEME -> PageRoute.LOOPBACK
    PageRoute.LOOPBACK -> PageRoute.INLINE
    PageRoute.INLINE -> PageRoute.NOTICE.takeIf { loopbackBound }
    PageRoute.NOTICE -> null
}

private const val HTTP_ERROR_FLOOR = 400

internal fun pageArrived(httpStatusCode: Int, loadFailed: Boolean): Boolean =
    !loadFailed && httpStatusCode >= 0 && httpStatusCode < HTTP_ERROR_FLOOR

class JcefHost(
    parentDisposable: Disposable,
    private val onMessage: (String) -> Unit,
) {

    val supported: Boolean = JBCefApp.isSupported()

    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?

    private var ready: Boolean = false

    private val pending = LinkedList<String>()

    private var page: Page? = null

    private var webReady: Boolean = false

    private var route: PageRoute? = null

    @Volatile
    private var loopback: LoopbackPageServer? = null

    @Volatile
    private var disposed: Boolean = false

    @Volatile
    private var mainFrameLoadFailed: Boolean = false

    private val readyWatchdog = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    private val deferred = ArrayList<ReadyBlock>()

    private val deferredAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

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

            Disposer.register(parentDisposable, b)

            Disposer.register(
                parentDisposable,
                Disposable {
                    disposed = true
                    loopback?.stop()
                },
            )

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

    fun whenWebReady(timeoutMs: Long = WEB_READY_TIMEOUT_MS, block: () -> Unit) {
        runOnEdt {
            if (webReady || browser == null) {
                block()
                return@runOnEdt
            }
            val entry = ReadyBlock(block)
            deferred.add(entry)
            deferredAlarm.addRequest({ runDeferred(entry) }, timeoutMs)
        }
    }

    fun markWebReady() {
        runOnEdt {
            webReady = true
            readyWatchdog.cancelAllRequests()
            loopback?.stop()
            if (inputComponent()?.isFocusOwner == true) grantCefFocus()
            flushDeferred()
        }
    }

    fun requestFocus() {
        runOnEdt {
            val target = inputComponent() ?: return@runOnEdt
            if (target.isFocusOwner) grantCefFocus() else IdeFocusManager.getGlobalInstance().requestFocus(target, true)
        }
    }

    private fun grantCefFocus() {
        runCatching { browser?.cefBrowser?.setFocus(true) }
        exec("window.cc.focusInput && window.cc.focusInput()")
    }

    fun inputComponent(): JComponent? {
        val b = browser ?: return null
        return runCatching { b.cefBrowser.uiComponent }.getOrNull() as? JComponent
    }

    fun dispose() {
        runOnEdt {
            deferredAlarm.cancelAllRequests()
            deferred.clear()
        }
        loopback?.stop()
        jsQuery?.let { Disposer.dispose(it) }
        browser?.let { Disposer.dispose(it) }
    }

    private class ReadyBlock(val block: () -> Unit)

    private fun flushDeferred() {
        deferredAlarm.cancelAllRequests()
        val queued = ArrayList(deferred)
        deferred.clear()
        queued.forEach { it.block() }
    }

    private fun runDeferred(entry: ReadyBlock) {
        if (!deferred.remove(entry)) return
        log.warn("Claude Code chat page has not announced itself in time — running a deferred action without it")
        entry.block()
    }

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
                    if (frame != null && !frame.isMain) return
                    if (!pageArrived(httpStatusCode, mainFrameLoadFailed)) {
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

    private fun startRoute(schemeAvailable: Boolean): PageRoute =
        maxOf(if (schemeAvailable) PageRoute.SCHEME else PageRoute.LOOPBACK, provenRoute)

    private fun deliver(b: JBCefBrowser, page: Page, next: PageRoute) {
        route = next
        if (next != PageRoute.NOTICE) armReadyWatchdog(b)
        when (next) {
            PageRoute.SCHEME -> b.loadURL(PAGE_URL)
            PageRoute.LOOPBACK -> serveOverLoopback(b, page)
            PageRoute.INLINE -> b.loadHTML(page.html)
            PageRoute.NOTICE -> loopback?.let { b.loadHTML(RemoteDevNotice.html(it.port)) }
        }
    }

    private fun armReadyWatchdog(b: JBCefBrowser) {
        if (webReady) return
        readyWatchdog.cancelAllRequests()
        readyWatchdog.addRequest({ if (!webReady) promote(b) }, READY_WATCHDOG_MS)
    }

    private fun relaxReadyWatchdog(b: JBCefBrowser) {
        if (webReady) return
        readyWatchdog.cancelAllRequests()
        readyWatchdog.addRequest({ if (!webReady) promote(b) }, SCRIPTS_WATCHDOG_MS)
    }

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

    private fun remember(reached: PageRoute) {
        if (reached == PageRoute.LOOPBACK) provenRoute = PageRoute.LOOPBACK
    }

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
        b.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean {
                    return userGesture
                }
            },
            b.cefBrowser,
        )

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

    private fun buildPage(): Page {
        val shell = readResource("shell.html")
            ?: return Page(FALLBACK_HTML, headersFor(cspWith("'none'", "'none'")))

        val styleInner = "\n" + CSS_PARTS.joinToString("\n") { readResource("css/$it").orEmpty() } + "\n"
        val cssBlock = "<style>$styleInner</style>"
        val styleSrc = "'sha256-" + sha256Base64(styleInner) + "'"

        val libNames = listOf("purify.min.js", "marked.min.js", "highlight.min.js")

        val contents = LinkedHashMap<String, String>()
        (libNames + appNames).forEach { name -> readResource(name)?.let { contents[name] = it } }

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

    private class Page(val html: String, val headers: Map<String, String>)

    private companion object {
        private val log = logger<JcefHost>()

        private val appNames = listOf(
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
            "app-session-gitchat.js",
            "app-session-guard.js",
            "app-session-vuln.js",
            "app-session.js",
            "app-tabs-base.js",
            "app-tabs-guard.js",
            "app-tabs-pill.js",
            "app-tabs-scroll.js",
            "app-tabs.js",
        )

        private val CSS_PARTS = listOf(
            "base.css",
            "transcript.css",
            "composer.css",
            "permissions.css",
            "dashboard.css",
            "git.css",
            "guard.css",
            "vuln.css",
            "boot.css",
            "tabs.css",
        )

        private const val READY_WATCHDOG_MS = 2500

        private const val SCRIPTS_WATCHDOG_MS = 20_000

        private const val WEB_READY_TIMEOUT_MS = 5_000L

        @Volatile
        private var provenRoute: PageRoute = PageRoute.SCHEME

        private const val HTTP_OK = 200

        private const val SCHEME = "http"
        private const val DOMAIN = "claude-code.localhost"
        private const val PAGE_URL = "http://claude-code.localhost/index.html"

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

        @Volatile
        private var schemeRegistered = false

        private const val FALLBACK_HTML =
            "<!doctype html><html><body style=\"font-family:sans-serif;padding:16px\">" +
                "Claude Code failed to load its UI resources." +
                "</body></html>"

        @Synchronized
        private fun registerScheme(page: Page): Boolean {
            if (schemeRegistered) return true
            schemeRegistered = runCatching {
                CefApp.getInstance().registerSchemeHandlerFactory(SCHEME, DOMAIN, SecurePageFactory(page))
            }.getOrDefault(false)
            return schemeRegistered
        }
    }

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
