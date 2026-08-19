package dev.lain.claudejb.session

enum class AuthFailure {
    NONE,

    EXPIRED,

    NO_IDENTITY,
}

object LoginDetection {

    private val LOGIN_HINTS = listOf(
        "/login",
        "please log in",
        "please login",
        "not logged in",
        "not authenticated",
        "authentication failed",
        "authentication error",
        "invalid api key",
        "unauthorized",
        "oauth",
        "log in to claude",
        "run `claude login`",
    )

    private val EXCLUSIONS = listOf(
        "credit balance",
        "rate limit",
        "quota",
        "overage",
        "usage limit",
    )

    private val EXPIRY_PHRASES = listOf(
        "oauth access token has expired",
        "access token has expired",
        "access token is expired",
        "oauth token has expired",
        "oauth token is expired",
    )

    private const val REFRESH_TOKEN = "refresh token"

    fun classify(text: String?): AuthFailure {
        val t = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return AuthFailure.NONE
        if (EXCLUSIONS.any { it in t }) return AuthFailure.NONE
        if (LOGIN_HINTS.none { it in t }) return AuthFailure.NONE
        if (REFRESH_TOKEN in t) return AuthFailure.NO_IDENTITY
        return if (EXPIRY_PHRASES.any { it in t }) AuthFailure.EXPIRED else AuthFailure.NO_IDENTITY
    }

    fun resolve(text: String?, renewable: () -> Boolean): AuthFailure {
        val failure = classify(text)
        if (failure != AuthFailure.EXPIRED) return failure
        return if (renewable()) AuthFailure.EXPIRED else AuthFailure.NO_IDENTITY
    }
}
