package com.aether.config.codec;

import com.aether.config.diagnostic.ConfigDecodeException;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import com.aether.config.view.ConfigValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Bidirectional codec transforming between {@link ConfigValue}/{@link ConfigNode} and domain objects.
 */
public interface ConfigCodec<T> {

    @FunctionalInterface
    interface Decoder<T> {
        T decode(ConfigValue value) throws ConfigDecodeException;
    }

    @FunctionalInterface
    interface Encoder<T> {
        ConfigNode encode(T value, CodecContext context);
    }

    T decode(ConfigValue value) throws ConfigDecodeException;

    default ConfigNode encode(T value, CodecContext context) {
        return ConfigDocument.toNode(value);
    }

    static <T> ConfigCodec<T> decoder(Decoder<T> decoder) {
        return decoder::decode;
    }

    static <T> ConfigCodec<T> of(Decoder<T> decoder, Encoder<T> encoder) {
        return new ConfigCodec<>() {
            @Override
            public T decode(ConfigValue value) throws ConfigDecodeException {
                return decoder.decode(value);
            }

            @Override
            public ConfigNode encode(T value, CodecContext context) {
                return encoder.encode(value, context);
            }
        };
    }

    /**
     * Tries multiple candidate codecs in order without polluting diagnostics on failed attempts.
     */
    @SafeVarargs
    static <T> ConfigCodec<T> oneOf(ConfigCodec<T>... candidates) {
        return oneOf(List.of(candidates));
    }

    static <T> ConfigCodec<T> oneOf(List<ConfigCodec<T>> candidates) {
        List<ConfigCodec<T>> copy = List.copyOf(candidates);
        return value -> {
            List<String> errors = new ArrayList<>(copy.size());
            for (ConfigCodec<T> candidate : copy) {
                ConfigDiagnostics scratch = new ConfigDiagnostics();
                ConfigValue probe = value.withDiagnostics(scratch);
                try {
                    T result = candidate.decode(probe);
                    if (!scratch.hasErrors()) {
                        value.diagnostics().addAll(scratch);
                        return result;
                    }
                    errors.add(scratch.formatAll());
                } catch (ConfigDecodeException ex) {
                    errors.add(ex.getMessage());
                }
            }
            throw new ConfigDecodeException("No matching variant: " + String.join(" | ", errors));
        };
    }
}
