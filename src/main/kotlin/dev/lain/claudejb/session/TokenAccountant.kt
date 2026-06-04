package dev.lain.claudejb.session

/**
 * Pure (no-IDE) token bookkeeping for a single chat session, extracted from [ClaudeSession].
 *
 * Two tiers are tracked:
 *  - **live**: the currently-streaming message's running totals. `message_delta` usage restarts near 0
 *    per assistant message, so it is folded into the session totals at each message boundary before the
 *    next message overwrites it.
 *  - **session**: the accumulated totals for the whole session (sum of all folded messages).
 *
 * All four components (input, cache-creation, cache-read, output) are tracked because
 * cache_creation_input_tokens alone is typically the largest line item in a chat, and showing only
 * output_tokens dramatically under-reports actual usage.
 *
 * Not thread-safe by itself: [ClaudeSession] confines every call to the EDT (single-threaded), which is
 * why no synchronization is needed here. The unit tests exercise it on a single thread.
 */
class TokenAccountant {
    var liveInputTokens: Int = 0; private set
    var liveCacheCreationTokens: Int = 0; private set
    var liveCacheReadTokens: Int = 0; private set
    var liveOutputTokens: Int = 0; private set

    var sessionInputTokens: Int = 0; private set
    var sessionCacheCreationTokens: Int = 0; private set
    var sessionCacheReadTokens: Int = 0; private set
    var sessionOutputTokens: Int = 0; private set

    /** Overwrites the in-flight message's live counters with the latest usage snapshot (replace, not add). */
    fun onLiveUsage(input: Int, cacheCreation: Int, cacheRead: Int, output: Int) {
        liveInputTokens = input
        liveCacheCreationTokens = cacheCreation
        liveCacheReadTokens = cacheRead
        liveOutputTokens = output
    }

    /** Folds the in-flight message's token counters into the session totals and resets the live counters. */
    fun foldIntoSession() {
        sessionInputTokens += liveInputTokens
        sessionCacheCreationTokens += liveCacheCreationTokens
        sessionCacheReadTokens += liveCacheReadTokens
        sessionOutputTokens += liveOutputTokens
        liveInputTokens = 0
        liveCacheCreationTokens = 0
        liveCacheReadTokens = 0
        liveOutputTokens = 0
    }

    /** Total tokens for the whole session including the message currently in flight. */
    fun totalTokens(): Int =
        sessionInputTokens + sessionCacheCreationTokens + sessionCacheReadTokens + sessionOutputTokens +
            liveInputTokens + liveCacheCreationTokens + liveCacheReadTokens + liveOutputTokens

    /** Resets all counters to zero. */
    fun reset() {
        liveInputTokens = 0
        liveCacheCreationTokens = 0
        liveCacheReadTokens = 0
        liveOutputTokens = 0
        sessionInputTokens = 0
        sessionCacheCreationTokens = 0
        sessionCacheReadTokens = 0
        sessionOutputTokens = 0
    }
}
