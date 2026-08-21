package dev.lain.claudejb.forge

sealed interface ForgeAnswer<out T> {

    data class Known<out T>(val value: T) : ForgeAnswer<T>

    data class Silent(val reason: ForgeSilence) : ForgeAnswer<Nothing>
}

enum class ForgeSilence {

    NO_BRANCH,

    NO_TOKEN,

    UNSUPPORTED_HOST,

    UNAUTHORIZED,

    NOT_VISIBLE,

    RATE_LIMITED,

    UNREACHABLE,

    OVERSIZED,

    MALFORMED,

    ON_EDT,
}
