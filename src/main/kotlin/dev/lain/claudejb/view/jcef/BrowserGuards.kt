package dev.lain.claudejb.view.jcef

import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

internal fun installNavigationGuards(b: JBCefBrowser, isOwnPage: (String?) -> Boolean) {
    b.jbCefClient.addRequestHandler(
        object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                userGesture: Boolean,
                isRedirect: Boolean,
            ): Boolean = !isOwnPage(request?.url)
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
