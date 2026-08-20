package dev.lain.claudejb.util;

import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;

public final class PluginIds {

    private PluginIds() {
    }

    public static @NotNull PluginId of(@NotNull String id) {
        return PluginId.getId(id);
    }
}
