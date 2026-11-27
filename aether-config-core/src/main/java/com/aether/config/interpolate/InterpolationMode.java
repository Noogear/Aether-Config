package com.aether.config.interpolate;

/**
 * Controls placeholder interpolation across scalar strings during configuration loading.
 */
public enum InterpolationMode {
    /** Do not interpolate {@code ${...}} placeholders. */
    DISABLED,
    /** Interpolate {@code ${env:VAR}}, {@code ${sys:PROP}}, and {@code ${NAME}} against environment/system properties. */
    ENV_AND_SYS,
    /** Interpolate environment variables, system properties, and cross-key references {@code ${ref:path.to.key}} / {@code ${path.to.key}}. */
    ALL
}
