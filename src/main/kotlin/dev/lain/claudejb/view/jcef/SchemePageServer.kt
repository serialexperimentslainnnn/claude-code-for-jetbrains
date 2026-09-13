package dev.lain.claudejb.view.jcef

import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.nio.charset.StandardCharsets

internal object SchemePageServer {

    const val PAGE_URL = "http://claude-code.localhost/index.html"

    private const val SCHEME = "http"
    private const val DOMAIN = "claude-code.localhost"
    private const val HTTP_OK = 200

    @Volatile
    private var registered = false

    @Synchronized
    fun register(page: Page): Boolean {
        if (registered) return true
        registered = runCatching {
            CefApp.getInstance().registerSchemeHandlerFactory(SCHEME, DOMAIN, Factory(page))
        }.getOrDefault(false)
        return registered
    }

    private class Factory(page: Page) : CefSchemeHandlerFactory {
        private val bytes: ByteArray = page.html.toByteArray(StandardCharsets.UTF_8)
        private val headers: Map<String, String> = page.headers

        override fun create(
            browser: CefBrowser?,
            frame: CefFrame?,
            schemeName: String?,
            request: CefRequest?,
        ): CefResourceHandler = Handler(bytes, headers)
    }

    private class Handler(
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
