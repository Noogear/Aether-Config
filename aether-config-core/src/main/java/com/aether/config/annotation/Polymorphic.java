package com.aether.config.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables polymorphic binding and encoding on an interface, abstract class, or {@code sealed} hierarchy
 * using a discriminator key (default {@code "type"}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Polymorphic {

    String typeKey() default "type";

    SubType[] value() default {};

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface SubType {
        String name();

        Class<?> type();
    }
}
