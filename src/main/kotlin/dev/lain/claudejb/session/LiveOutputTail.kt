package dev.lain.claudejb.session

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the part of a progress file that has appeared since the last read.
 *
 * **Why a tail and not a re-read.** A backgrounded agent's `outputFile` grows for as long as the agent runs;
 * re-reading it whole on every poll would be O(size) per poll and would hand the UI the same text over and
 * over. Keeping the offset means each poll costs only what is new, which is what makes polling a running task
 * affordable at all.
 *
 * **Why polling and not a watcher.** The file is written by another process, often on a filesystem where
 * `WatchService` degrades to polling anyway (and, on macOS, does so with a delay measured in seconds). The
 * caller already has a scan loop; this just answers "what is new" when asked.
 *
 * Pure enough to test: it takes paths and returns strings, holds only offsets, and never touches the UI. IO
 * is blocking — call it off the EDT. Every failure is answered with an empty string rather than an exception:
 * the file may not exist yet, may be being rewritten, or may be gone. None of that is worth breaking a scan.
 */
class LiveOutputTail {

    /** path → how many bytes of it have already been handed out. */
    private val offsets = ConcurrentHashMap<String, Long>()

    /**
     * The bytes appended to [path] since the last call, decoded as UTF-8. Empty when there is nothing new.
     *
     * A file that SHRANK is treated as a new file and read from the start: that is what a rotation or a
     * rewrite looks like from here, and continuing from a stale offset would read from the middle of the new
     * content and hand back a fragment.
     */
    fun readNew(path: Path): String {
        val key = path.toString()
        return runCatching {
            if (!Files.isRegularFile(path)) return@runCatching ""
            val size = Files.size(path)
            val from = offsets[key]?.takeIf { it <= size } ?: 0L
            if (size <= from) {
                offsets[key] = size
                return@runCatching ""
            }
            val length = (size - from).coerceAtMost(MAX_CHUNK)
            val buffer = ByteArray(length.toInt())
            Files.newByteChannel(path).use { channel ->
                channel.position(size - length)
                var read = 0
                while (read < buffer.size) {
                    val n = channel.read(java.nio.ByteBuffer.wrap(buffer, read, buffer.size - read))
                    if (n <= 0) break
                    read += n
                }
                offsets[key] = size
                String(buffer, 0, read, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    /** Forgets [path]'s offset, so the next read starts from the beginning. */
    fun forget(path: Path) {
        offsets.remove(path.toString())
    }

    fun clear() = offsets.clear()

    private companion object {
        /**
         * Most that is handed over in one poll. A task can write megabytes between two polls (a build log,
         * a `tail -f`), and the point of this view is what is happening now — so a burst is truncated to its
         * tail rather than being pushed whole into a web view row.
         */
        const val MAX_CHUNK = 64L * 1024
    }
}
