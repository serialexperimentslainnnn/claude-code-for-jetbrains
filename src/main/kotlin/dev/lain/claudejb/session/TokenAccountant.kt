package dev.lain.claudejb.session

class TokenAccountant {
    var liveInputTokens: Int = 0
        private set
    var liveCacheCreationTokens: Int = 0
        private set
    var liveCacheReadTokens: Int = 0
        private set
    var liveOutputTokens: Int = 0
        private set

    var sessionInputTokens: Int = 0
        private set
    var sessionCacheCreationTokens: Int = 0
        private set
    var sessionCacheReadTokens: Int = 0
        private set
    var sessionOutputTokens: Int = 0
        private set

    fun onLiveUsage(input: Int, cacheCreation: Int, cacheRead: Int, output: Int) {
        liveInputTokens = input
        liveCacheCreationTokens = cacheCreation
        liveCacheReadTokens = cacheRead
        liveOutputTokens = output
    }

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

    fun totalTokens(): Int =
        sessionInputTokens + sessionCacheCreationTokens + sessionCacheReadTokens + sessionOutputTokens +
            liveInputTokens + liveCacheCreationTokens + liveCacheReadTokens + liveOutputTokens

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
