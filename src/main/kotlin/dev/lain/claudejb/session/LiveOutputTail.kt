package dev.lain.claudejb.session

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class LiveOutputTail {

    private val offsets = ConcurrentHashMap<String, Long>()

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

    fun forget(path: Path) {
        offsets.remove(path.toString())
    }

    fun clear() = offsets.clear()

    private companion object {
        const val MAX_CHUNK = 64L * 1024
    }
}
