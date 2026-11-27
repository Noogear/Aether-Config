package com.aether.config.bind;

/**
 * Policy applied when keys in the configuration file do not match the target schema.
 */
public enum UnknownKeyPolicy {
    /** Report a warning with spelling suggestion while preserving the key on disk. */
    WARN,
    /** Report an error diagnostic without deleting the key. */
    ERROR,
    /** Silently ignore unknown keys. */
    IGNORE,
    /** Warn and remove unknown keys when patching/saving. */
    PRUNE
}
