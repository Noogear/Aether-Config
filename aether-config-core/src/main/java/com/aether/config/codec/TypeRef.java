package com.aether.config.codec;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Objects;

/**
 * Zero-dependency generic type capture token (e.g. {@code new TypeRef<Map<String, List<Integer>>>() {}}).
 */
public class TypeRef<T> {

    private final Type type;
    private final Class<?> rawType;

    protected TypeRef() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType pt)) {
            throw new IllegalStateException("TypeRef must be constructed with an explicit type argument");
        }
        this.type = pt.getActualTypeArguments()[0];
        this.rawType = resolveRawType(this.type);
    }

    private TypeRef(Type type) {
        this.type = Objects.requireNonNull(type, "type");
        this.rawType = resolveRawType(type);
    }

    public static <T> TypeRef<T> of(Class<T> type) {
        return new TypeRef<>(type);
    }

    public static TypeRef<?> of(Type type) {
        return new TypeRef<>(type);
    }

    public Type type() {
        return type;
    }

    public Class<?> rawType() {
        return rawType;
    }

    public static Class<?> resolveRawType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            return raw instanceof Class<?> c ? c : Object.class;
        }
        if (type instanceof GenericArrayType gat) {
            Class<?> component = resolveRawType(gat.getGenericComponentType());
            return Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            return upper.length > 0 ? resolveRawType(upper[0]) : Object.class;
        }
        if (type instanceof TypeVariable<?> tv) {
            Type[] bounds = tv.getBounds();
            return bounds.length > 0 ? resolveRawType(bounds[0]) : Object.class;
        }
        return Object.class;
    }

    public static @Nullable Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (index >= 0 && index < args.length) {
                return unwrapWildcard(args[index]);
            }
        }
        return null;
    }

    private static Type unwrapWildcard(Type type) {
        if (type instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) {
                return upper[0];
            }
        }
        return type;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TypeRef<?> other && Objects.equals(type, other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type);
    }

    @Override
    public String toString() {
        return type.getTypeName();
    }
}
