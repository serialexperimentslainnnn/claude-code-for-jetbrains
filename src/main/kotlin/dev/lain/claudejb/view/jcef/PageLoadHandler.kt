package dev.lain.claudejb.view.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

internal class PageLoadHandler(
    private val onStarted: () -> Unit,
    private val onArrived: () -> Unit,
    private val onMissed: (Int) -> Unit,
) : CefLoadHandlerAdapter() {

    @Volatile
    private var mainFrameLoadFailed: Boolean = false

    override fun onLoadStart(cefBrowser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
        if (frame != null && !frame.isMain) return
        mainFrameLoadFailed = false
        onStarted()
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
            onMissed(httpStatusCode)
            return
        }
        onArrived()
    }
}
