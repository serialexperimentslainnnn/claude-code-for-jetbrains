package dev.lain.claudejb.util;

import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;

/**
 * Builds a {@link PluginId} — from <b>Java</b>, on purpose.
 *
 * <p>{@code PluginId} became a <i>Kotlin</i> class in 2025.2. Called from Kotlin, {@code PluginId.getId(…)}
 * compiles to a reference to {@code PluginId.Companion}, a symbol that does not exist in older IDEs: the plugin
 * installs happily on 2024.2–2025.1 and then dies with {@code NoSuchFieldError} the first time it asks whether a
 * plugin is installed. (Caught by {@code verifyPlugin} against IC-251 — it is a <i>binary</i> incompatibility, so
 * the compiler never sees it.)
 *
 * <p>javac has no such notion: it emits a plain {@code invokestatic PluginId.getId}, which resolves on every IDE,
 * old and new. Hence this one-method Java class. The obvious Kotlin workaround — scanning
 * {@code PluginManagerCore.getPlugins()} for a matching id — is worse: that method is {@code @ApiStatus.Internal},
 * and the Marketplace rejects plugins that touch internal API.
 */
public final class PluginIds {

    private PluginIds() {
    }

    /** The {@link PluginId} for {@code id}, resolved the way every IDE version understands. */
    public static @NotNull PluginId of(@NotNull String id) {
        return PluginId.getId(id);
    }
}
