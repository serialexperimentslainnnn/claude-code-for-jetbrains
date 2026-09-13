package dev.lain.claudejb.view.jcef

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import dev.lain.claudejb.util.edtNow
import dev.lain.claudejb.util.logger
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.handler.CefDisplayHandlerAdapter
import java.util.LinkedList
import javax.swing.JComponent
import javax.swing.border.EmptyBorder

class JcefHost(
    parentDisposable: Disposable,
    private val onMessage: (String) -> Unit,
    onPageLost: () -> Unit = {},
) {

    val supported: Boolean = JBCefApp.isSupported()

    private val browser: JBCefBrowser?

    private var ready: Boolean = false

    private val pending = LinkedList<String>()

    private var webReady: Boolean = false

    @Volatile
    private var disposed: Boolean = false

    private var delivery: PageDelivery? = null

    private val deferred = ArrayList<ReadyBlock>()

    private val deferredAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    val component: JComponent

    init {
        if (!supported) {
            browser = null
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
                    deferredAlarm.cancelAllRequests()
                    deferred.clear()
                    delivery?.stopLoopback()
                },
            )

            val base: JBCefBrowserBase = b
            val query = JBCefJSQuery.create(base)
            Disposer.register(parentDisposable, query)
            query.addHandler { request ->
                ApplicationManager.getApplication().invokeLater { onMessage(request) }
                null
            }

            installNavigationGuards(b, ::isOwnPage)
            b.jbCefClient.addLoadHandler(
                PageLoadHandler(
                    onStarted = { delivery?.pageLoadStarted() },
                    onArrived = { drainInto(b, query) },
                    onMissed = ::pageMissed,
                ),
                b.cefBrowser,
            )
            b.jbCefClient.addDisplayHandler(ConsoleRelay(), b.cefBrowser)

            val d = PageDelivery(
                browser = b,
                page = PageAssembly.build(),
                parentDisposable = parentDisposable,
                webReady = { webReady },
                onRedeliver = { ready = false },
                isDisposed = { disposed },
                onExhausted = onPageLost,
            )
            delivery = d
            d.start()
        }
    }

    fun exec(js: String) {
        val b = browser ?: return
        edtNow {
            if (disposed) return@edtNow
            if (ready) {
                executeNow(b, js)
            } else {
                pending.add(js)
            }
        }
    }

    fun execBuilt(method: String, build: () -> String?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val payload = runCatching(build)
                .onFailure { log.warn("Claude Code: $method could not be answered", it) }
                .getOrNull() ?: return@executeOnPooledThread
            exec("$method && $method($payload)")
        }
    }

    fun whenWebReady(timeoutMs: Long = WEB_READY_TIMEOUT_MS, block: () -> Unit) {
        edtNow {
            if (webReady || browser == null) {
                block()
                return@edtNow
            }
            val entry = ReadyBlock(block)
            deferred.add(entry)
            deferredAlarm.addRequest({ runDeferred(entry) }, timeoutMs)
        }
    }

    fun markWebReady() {
        edtNow {
            webReady = true
            delivery?.cancelWatchdog()
            delivery?.stopLoopback()
            if (inputComponent()?.isFocusOwner == true) grantCefFocus()
            flushDeferred()
        }
    }

    fun requestFocus() {
        edtNow {
            val target = inputComponent() ?: return@edtNow
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

    private fun pageMissed(httpStatusCode: Int) = edtNow {
        log.warn(
            "Claude Code chat page did not load over ${delivery?.route ?: "an unknown route"} " +
                "(http status $httpStatusCode) — keeping the queued state for the next one",
        )
    }

    private fun drainInto(b: JBCefBrowser, query: JBCefJSQuery) {
        executeNow(b, "window.__ccSend = function(p){ " + query.inject("p") + " };")
        edtNow {
            ready = true
            delivery?.relaxWatchdog()
            while (pending.isNotEmpty()) {
                executeNow(b, pending.poll())
            }
        }
    }

    private fun isOwnPage(url: String?): Boolean =
        delivery?.isOwnPage(url) ?: isOwnPageUrl(url, SchemePageServer.PAGE_URL, null)

    private fun executeNow(b: JBCefBrowser, js: String) {
        val url = b.cefBrowser.url ?: SchemePageServer.PAGE_URL
        val guarded = "try{" + js + "}catch(e){try{window.__ccSend&&window.__ccSend(JSON.stringify(" +
            "{type:'diag',report:'uncaught exec: '+((e&&e.stack)||e)}))}catch(_){}}"
        b.cefBrowser.executeJavaScript(guarded, url, 0)
    }

    private class ConsoleRelay : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            browser: CefBrowser?,
            level: CefSettings.LogSeverity?,
            message: String?,
            source: String?,
            line: Int,
        ): Boolean {
            val text = "chat page console [${level?.name?.removePrefix("LOGSEVERITY_")?.lowercase()}] $message ($source:$line)"
            when (level) {
                CefSettings.LogSeverity.LOGSEVERITY_ERROR,
                CefSettings.LogSeverity.LOGSEVERITY_FATAL,
                CefSettings.LogSeverity.LOGSEVERITY_WARNING,
                -> log.warn(text)

                else -> log.debug { text }
            }
            return false
        }
    }

    private companion object {
        private val log = logger<JcefHost>()

        private const val WEB_READY_TIMEOUT_MS = 5_000L
    }
}
