package com.aether.config.annotation;

import com.aether.config.diagnostic.ConfigDecodeException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Assigns a custom {@link ConfigValidator} to validate or normalize a decoded field value.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Check {

    Class<? extends ConfigValidator<?>> value();

    @FunctionalInterface
    interface ConfigValidator<T> {
        T validate(T value) throws ConfigDecodeException;
    }
}
