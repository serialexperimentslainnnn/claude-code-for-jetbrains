package dev.lain.claudejb.view.jcef

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import dev.lain.claudejb.util.edtNow
import dev.lain.claudejb.util.logger

internal class PageDelivery(
    private val browser: JBCefBrowser,
    private val page: Page,
    parentDisposable: Disposable,
    private val webReady: () -> Boolean,
    private val onRedeliver: () -> Unit,
    private val isDisposed: () -> Boolean,
    private val onExhausted: () -> Unit = {},
) {

    private val log = logger<PageDelivery>()

    private val readyWatchdog = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    var route: PageRoute? = null
        private set

    @Volatile
    private var loopback: LoopbackPageServer? = null

    fun start() = deliver(startRoute(schemeAvailable = SchemePageServer.register(page)))

    fun isOwnPage(url: String?): Boolean = isOwnPageUrl(url, SchemePageServer.PAGE_URL, loopback?.url)

    fun pageLoadStarted() = edtNow { if (route != null) arm(READY_WATCHDOG_MS) }

    fun relaxWatchdog() = arm(SCRIPTS_WATCHDOG_MS)

    fun cancelWatchdog() = readyWatchdog.cancelAllRequests()

    fun stopLoopback() {
        loopback?.stop()
    }

    private fun arm(delayMs: Int) {
        if (webReady()) return
        readyWatchdog.cancelAllRequests()
        readyWatchdog.addRequest({ if (!webReady()) promote() }, delayMs)
    }

    private fun startRoute(schemeAvailable: Boolean): PageRoute =
        maxOf(if (schemeAvailable) PageRoute.SCHEME else PageRoute.LOOPBACK, provenRoute)

    private fun deliver(next: PageRoute) {
        route = next
        when (next) {
            PageRoute.SCHEME -> browser.loadURL(SchemePageServer.PAGE_URL)
            PageRoute.LOOPBACK -> serveOverLoopback()
        }
    }

    private fun promote() {
        val current = route ?: return
        val next = nextPageRoute(current)
        if (next == null) {
            log.warn("Claude Code chat did not come up over $current and there is no route left to try")
            onExhausted()
            return
        }
        onRedeliver()
        log.warn("Claude Code chat did not come up over $current — delivering it over $next instead")
        provenRoute = next
        deliver(next)
    }

    private fun serveOverLoopback() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val server = LoopbackPageServer.start(page.html, page.headers)
            edtNow {
                if (isDisposed() || route != PageRoute.LOOPBACK) {
                    server?.stop()
                    return@edtNow
                }
                if (server == null) {
                    log.warn("Claude Code could not bind a loopback port for the chat page")
                    promote()
                } else {
                    loopback = server
                    browser.loadURL(server.url)
                }
            }
        }
    }

    private companion object {
        const val READY_WATCHDOG_MS = 2500

        const val SCRIPTS_WATCHDOG_MS = 20_000

        @Volatile
        var provenRoute: PageRoute = PageRoute.SCHEME
    }
}
