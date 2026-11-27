package com.aether.config.bind;

import com.aether.config.interpolate.InterpolationMode;

/**
 * Immutable options controlling schema binding and synchronization behavior.
 */
public record BindOptions(
        NamingStrategy naming,
        UnknownKeyPolicy unknownKeyPolicy,
        SyncMode syncMode,
        boolean strictMode,
        InterpolationMode interpolation
) {
    public static final BindOptions DEFAULT = new BindOptions(
            NamingStrategy.KEBAB_CASE,
            UnknownKeyPolicy.WARN,
            SyncMode.PATCH_MISSING,
            false,
            InterpolationMode.ENV_AND_SYS
    );
}
