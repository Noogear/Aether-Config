package com.aether.config.codec;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;

/**
 * Synthesizes a {@link ConfigCodec} dynamically for parameterized or open type families (Enums, Collections, Maps, etc.).
 */
@FunctionalInterface
public interface CodecFactory {

    @Nullable ConfigCodec<?> create(Type type, CodecRegistry registry);
}
