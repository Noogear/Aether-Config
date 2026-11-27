package com.aether.config.bind;

/**
 * Controls how defaults and missing keys are synchronized back to disk during load.
 */
public enum SyncMode {
    /** Never modify files on disk during load. */
    READ_ONLY,
    /** Generate default file only if it does not exist yet. */
    CREATE_IF_MISSING,
    /** Generate default file if missing AND non-destructively patch missing keys with comments into existing files. */
    PATCH_MISSING
}
