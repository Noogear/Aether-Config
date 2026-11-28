package com.aether.config.core;

import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.Objects;

/**
 * Supplies default resource streams (typically {@code plugin::getResource} or {@code ClassLoader::getResourceAsStream}).
 */
@FunctionalInterface
public interface ResourceProvider {

    @Nullable InputStream open(String resourcePath);

    static ResourceProvider fromClassLoader(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        return path -> {
            if (path == null || path.isEmpty()) {
                return null;
            }
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            return classLoader.getResourceAsStream(normalized);
        };
    }
}
