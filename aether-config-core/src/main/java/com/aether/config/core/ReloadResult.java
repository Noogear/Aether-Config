package com.aether.config.core;

import com.aether.config.diagnostic.ConfigDiagnostics;

/**
 * Outcome of loading or reloading a configuration snapshot.
 *
 * @param success     {@code true} when reload completed without fatal errors
 * @param changed     {@code true} when files changed or snapshot was updated
 * @param value       active snapshot (previous valid snapshot is preserved if reload failed)
 * @param diagnostics diagnostics collected during this load/reload attempt
 */
public record ReloadResult<T>(
        boolean success,
        boolean changed,
        T value,
        ConfigDiagnostics diagnostics
) {
}
