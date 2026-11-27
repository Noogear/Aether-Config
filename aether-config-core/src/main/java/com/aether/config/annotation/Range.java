package com.aether.config.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces numeric lower/upper bounds on a field or record component.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Range {
    double min() default -Double.MAX_VALUE;

    double max() default Double.MAX_VALUE;

    /**
     * When true, out-of-range values are clamped with a warning; when false, an error diagnostic is recorded.
     */
    boolean clamp() default true;
}
