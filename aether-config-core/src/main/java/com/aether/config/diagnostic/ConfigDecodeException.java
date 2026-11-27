package com.aether.config.diagnostic;

/**
 * Thrown by {@code ConfigCodec} implementations when a node cannot be decoded.
 */
public final class ConfigDecodeException extends RuntimeException {

    public ConfigDecodeException(String message) {
        super(message);
    }

    public ConfigDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
