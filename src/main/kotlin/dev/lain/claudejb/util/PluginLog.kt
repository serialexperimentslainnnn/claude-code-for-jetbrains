package dev.lain.claudejb.util

import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class PluginLog internal constructor(private val category: String, private val backend: Backend) {

    interface Backend {
        val isDebugEnabled: Boolean
        fun warn(message: String, cause: Throwable?)
        fun info(message: String)
        fun debug(message: String)
        fun setDebug(on: Boolean)
    }

    val isDebugEnabled: Boolean get() = backend.isDebugEnabled

    fun warn(message: String, cause: Throwable? = null) {
        val text = LogRedaction.apply(message)
        backend.warn(text, cause)
        LogRing.add(LogRing.Level.WARN, category, if (cause == null) text else "$text — $cause")
    }

    fun info(message: String) {
        val text = LogRedaction.apply(message)
        backend.info(text)
        LogRing.add(LogRing.Level.INFO, category, text)
    }

    fun debug(message: () -> String) {
        if (!backend.isDebugEnabled) return
        val text = LogRedaction.apply(message())
        backend.debug(text)
        LogRing.add(LogRing.Level.DEBUG, category, text)
    }

    private class PlatformBackend(private val logger: Logger) : Backend {
        override val isDebugEnabled: Boolean get() = logger.isDebugEnabled

        override fun warn(message: String, cause: Throwable?) = if (cause == null) logger.warn(message) else logger.warn(message, cause)

        override fun info(message: String) = logger.info(message)

        override fun debug(message: String) = logger.debug(message)

        override fun setDebug(on: Boolean) = logger.setLevel(if (on) LogLevel.DEBUG else LogLevel.INFO)
    }

    companion object {
        private const val PACKAGE_PREFIX = "dev.lain.claudejb."

        private val registry = ConcurrentHashMap<String, PluginLog>()

        @Volatile
        var debugOn: Boolean = System.getProperty("claudejb.debug") == "true"
            private set

        fun of(owner: KClass<*>): PluginLog = registry.computeIfAbsent(owner.java.name) { name ->
            PluginLog(name.removePrefix(PACKAGE_PREFIX), PlatformBackend(Logger.getInstance(owner.java))).also {
                if (debugOn) it.backend.setDebug(true)
            }
        }

        fun setDebug(on: Boolean) {
            debugOn = on
            registry.values.forEach { it.backend.setDebug(on) }
        }

        internal fun register(log: PluginLog, key: String) {
            registry[key] = log
        }
    }
}

inline fun <reified T : Any> logger(): PluginLog = PluginLog.of(T::class)

inline fun <reified T : Any> T.thisLogger(): PluginLog = PluginLog.of(T::class)
