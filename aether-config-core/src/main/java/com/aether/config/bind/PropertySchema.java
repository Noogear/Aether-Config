package com.aether.config.bind;

import com.aether.config.annotation.Check;
import com.aether.config.annotation.Comment;
import com.aether.config.annotation.Default;
import com.aether.config.annotation.Ignore;
import com.aether.config.annotation.Inline;
import com.aether.config.annotation.Key;
import com.aether.config.annotation.NonBlank;
import com.aether.config.annotation.Pattern;
import com.aether.config.annotation.Range;
import com.aether.config.annotation.Version;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reflection metadata abstracting over Java {@code record} components and POJO fields.
 */
public final class PropertySchema {

    private static final Map<Class<?>, List<PropertySchema>> SCHEMA_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> RECORD_CTOR_CACHE = new ConcurrentHashMap<>();

    private final String javaName;
    private final Class<?> rawType;
    private final Type genericType;
    private final @Nullable Key keyAnnotation;
    private final @Nullable Default defaultAnnotation;
    private final @Nullable Comment commentAnnotation;
    private final @Nullable Inline inlineAnnotation;
    private final @Nullable Range rangeAnnotation;
    private final @Nullable NonBlank nonBlankAnnotation;
    private final @Nullable Pattern patternAnnotation;
    private final @Nullable Check checkAnnotation;
    private final @Nullable Version versionAnnotation;

    private final @Nullable VarHandle varHandle;
    private final @Nullable MethodHandle getter;
    private final @Nullable MethodHandle setter;

    private PropertySchema(
            String javaName,
            Class<?> rawType,
            Type genericType,
            AnnotatedElement element,
            @Nullable VarHandle varHandle,
            @Nullable MethodHandle getter,
            @Nullable MethodHandle setter
    ) {
        this.javaName = javaName;
        this.rawType = rawType;
        this.genericType = genericType;
        this.keyAnnotation = getAnn(element, Key.class);
        this.defaultAnnotation = getAnn(element, Default.class);
        this.commentAnnotation = getAnn(element, Comment.class);
        this.inlineAnnotation = getAnn(element, Inline.class);
        this.rangeAnnotation = getAnn(element, Range.class);
        this.nonBlankAnnotation = getAnn(element, NonBlank.class);
        this.patternAnnotation = getAnn(element, Pattern.class);
        this.checkAnnotation = getAnn(element, Check.class);
        this.versionAnnotation = getAnn(element, Version.class);
        this.varHandle = varHandle;
        this.getter = getter;
        this.setter = setter;
    }

    private static <A extends Annotation> @Nullable A getAnn(AnnotatedElement el, Class<A> type) {
        return el.getAnnotation(type);
    }

    public static List<PropertySchema> forType(Class<?> type) {
        return SCHEMA_CACHE.computeIfAbsent(type, PropertySchema::introspect);
    }

    public static Constructor<?> recordCanonicalConstructor(Class<?> recordClass) {
        return RECORD_CTOR_CACHE.computeIfAbsent(recordClass, cls -> {
            Class<?>[] paramTypes = Arrays.stream(cls.getRecordComponents())
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            try {
                Constructor<?> ctor = cls.getDeclaredConstructor(paramTypes);
                ctor.setAccessible(true);
                return ctor;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Missing canonical constructor on record " + cls.getName(), e);
            }
        });
    }

    private static List<PropertySchema> introspect(Class<?> type) {
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            List<PropertySchema> schemas = new ArrayList<>(components.length);
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
                for (RecordComponent rc : components) {
                    MethodHandle accessor = lookup.unreflect(rc.getAccessor());
                    schemas.add(new PropertySchema(
                            rc.getName(),
                            rc.getType(),
                            rc.getGenericType(),
                            rc,
                            null,
                            accessor,
                            null
                    ));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to introspect record " + type.getName(), e);
            }
            return List.copyOf(schemas);
        }

        List<PropertySchema> schemas = new ArrayList<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.add(0, c);
        }
        for (Class<?> cls : hierarchy) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
                for (Field field : cls.getDeclaredFields()) {
                    int mod = field.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || Modifier.isFinal(mod)
                            || field.isAnnotationPresent(Ignore.class) || field.isSynthetic()) {
                        continue;
                    }
                    field.setAccessible(true);
                    VarHandle vh = field.getType().isPrimitive() ? lookup.unreflectVarHandle(field) : null;
                    MethodHandle get = vh == null ? lookup.unreflectGetter(field) : null;
                    MethodHandle set = vh == null ? lookup.unreflectSetter(field) : null;
                    schemas.add(new PropertySchema(
                            field.getName(),
                            field.getType(),
                            field.getGenericType(),
                            field,
                            vh,
                            get,
                            set
                    ));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to introspect fields on " + cls.getName(), e);
            }
        }
        return List.copyOf(schemas);
    }

    public String resolveKey(NamingStrategy naming) {
        if (keyAnnotation != null && !keyAnnotation.value().isEmpty()) {
            return keyAnnotation.value();
        }
        if (versionAnnotation != null && !versionAnnotation.key().isEmpty()) {
            return versionAnnotation.key();
        }
        return naming.format(javaName);
    }

    public String javaName() {
        return javaName;
    }

    public Class<?> rawType() {
        return rawType;
    }

    public Type genericType() {
        return genericType;
    }

    public @Nullable Default defaultValue() {
        return defaultAnnotation;
    }

    public @Nullable Comment comment() {
        return commentAnnotation;
    }

    public @Nullable Inline inline() {
        return inlineAnnotation;
    }

    public @Nullable Range range() {
        return rangeAnnotation;
    }

    public @Nullable NonBlank nonBlank() {
        return nonBlankAnnotation;
    }

    public @Nullable Pattern pattern() {
        return patternAnnotation;
    }

    public @Nullable Check check() {
        return checkAnnotation;
    }

    public @Nullable Version version() {
        return versionAnnotation;
    }

    public @Nullable Object get(Object instance) {
        try {
            if (varHandle != null) {
                return varHandle.get(instance);
            }
            if (getter != null) {
                return getter.invoke(instance);
            }
            throw new IllegalStateException("No getter for property " + javaName);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to read property '" + javaName + "'", t);
        }
    }

    public void set(Object instance, @Nullable Object value) {
        try {
            if (varHandle != null) {
                varHandle.set(instance, value);
            } else if (setter != null) {
                setter.invoke(instance, value);
            } else {
                throw new IllegalStateException("No setter for property " + javaName);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to write property '" + javaName + "'", t);
        }
    }
}
