package dev.lain.claudejb.view.jcef

internal enum class PageRoute { SCHEME, LOOPBACK }

internal fun nextPageRoute(current: PageRoute): PageRoute? = when (current) {
    PageRoute.SCHEME -> PageRoute.LOOPBACK
    PageRoute.LOOPBACK -> null
}

internal fun isOwnPageUrl(url: String?, pageUrl: String, loopbackUrl: String?): Boolean {
    val target = url?.trim().orEmpty()
    if (target.isEmpty() || target.equals("about:blank", ignoreCase = true)) return true
    if (target.startsWith(pageUrl, ignoreCase = true)) return true
    return loopbackUrl?.takeIf { it.isNotBlank() }?.let { target.startsWith(it, ignoreCase = true) } == true
}

private const val HTTP_ERROR_FLOOR = 400

internal fun pageArrived(httpStatusCode: Int, loadFailed: Boolean): Boolean =
    !loadFailed && httpStatusCode >= 0 && httpStatusCode < HTTP_ERROR_FLOOR
