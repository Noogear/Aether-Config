package com.aether.config.codec;

import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;

/**
 * Context supplied to {@link ConfigCodec#encode(Object, CodecContext)} for nested serialization.
 */
public final class CodecContext {

    private final CodecRegistry registry;

    public CodecContext(CodecRegistry registry) {
        this.registry = registry;
    }

    public CodecRegistry registry() {
        return registry;
    }

    @SuppressWarnings("unchecked")
    public ConfigNode encode(@Nullable Object value, @Nullable Type targetType) {
        if (value == null) {
            return ConfigNode.NullNode.INSTANCE;
        }
        Type effectiveType = targetType != null ? targetType : value.getClass();
        var codecOpt = registry.find(effectiveType);
        if (codecOpt.isPresent()) {
            return ((ConfigCodec<Object>) codecOpt.get()).encode(value, this);
        }
        return ConfigDocument.toNode(value);
    }
}
