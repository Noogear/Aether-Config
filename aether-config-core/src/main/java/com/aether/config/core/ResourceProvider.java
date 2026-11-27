package com.aether.config.core;

import org.jetbrains.annotations.Nullable;

import java.io.InputStream;

/**
 * Supplies default resource streams (typically {@code plugin::getResource} or {@code ClassLoader::getResourceAsStream}).
 */
@FunctionalInterface
public interface ResourceProvider {

    @Nullable InputStream open(String resourcePath);
}
