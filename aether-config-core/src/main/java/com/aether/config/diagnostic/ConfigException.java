package com.aether.config.diagnostic;

/**
 * Thrown in strict mode when configuration loading or validation produces errors.
 */
public final class ConfigException extends RuntimeException {

    private final ConfigDiagnostics diagnostics;

    public ConfigException(String message, ConfigDiagnostics diagnostics) {
        super(message + (diagnostics != null && !diagnostics.isEmpty() ? "\n" + diagnostics.formatAll() : ""));
        this.diagnostics = diagnostics != null ? diagnostics : new ConfigDiagnostics();
    }

    public ConfigException(String message, Throwable cause, ConfigDiagnostics diagnostics) {
        super(message, cause);
        this.diagnostics = diagnostics != null ? diagnostics : new ConfigDiagnostics();
    }

    public ConfigDiagnostics diagnostics() {
        return diagnostics;
    }
}
