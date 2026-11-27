package com.aether.config.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares schema version tracking for automatic backups and functional migrations.
 */
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Version {
    int value() default 1;

    String key() default "config-version";

    boolean autoBackup() default true;
}
