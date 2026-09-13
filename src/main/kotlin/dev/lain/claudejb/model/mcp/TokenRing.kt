package dev.lain.claudejb.model.mcp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class TokenRing(
    private val overlapMillis: Long = DEFAULT_OVERLAP_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    @Volatile
    private var current: String = generate()

    @Volatile
    private var previous: String? = null

    @Volatile
    private var previousExpiresAt: Long = 0

    val token: String get() = current

    fun rotate(): String {
        previous = current
        previousExpiresAt = clock() + overlapMillis
        current = generate()
        return current
    }

    fun accepts(candidate: String?): Boolean {
        if (candidate == null) return false
        if (same(candidate, current)) return true
        val old = previous ?: return false
        return clock() < previousExpiresAt && same(candidate, old)
    }

    private fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    companion object {
        const val DEFAULT_OVERLAP_MILLIS = 60_000L
        const val ROTATION_MILLIS = 30L * 60 * 1000
        private const val TOKEN_BYTES = 32
    }
}
