package com.aether.config.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls default key seeding for {@code Map<String, T>} configuration entries.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Template {

    Strategy value() default Strategy.FORCE;

    String name() default "default";

    enum Strategy {
        /** Always ensure the template key exists in the map. */
        FORCE,
        /** Add the template key only when the map is empty. */
        SMART,
        /** Never auto-populate template entries. */
        STRICT;

        public boolean shouldAdd(boolean hasTemplateKey, boolean isEmpty) {
            return switch (this) {
                case FORCE -> !hasTemplateKey;
                case SMART -> isEmpty;
                case STRICT -> false;
            };
        }
    }
}
