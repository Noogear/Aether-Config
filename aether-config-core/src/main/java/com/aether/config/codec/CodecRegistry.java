package com.aether.config.codec;

import com.aether.config.diagnostic.ConfigDecodeException;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.NodeComments;
import com.aether.config.node.ScalarStyle;
import com.aether.config.node.SourceSpan;
import com.aether.config.view.ConfigSection;
import com.aether.config.view.ConfigValue;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Thread-safe, instance-scoped registry of {@link ConfigCodec} decoders/encoders and {@link CodecFactory} resolvers.
 */
public final class CodecRegistry {

    private static final Pattern DURATION_TOKEN = Pattern.compile(
            "^([+-]?\\d+(?:\\.\\d+)?)\\s*(ms|millis?|s|sec|seconds?|m|min|minutes?|h|hr|hours?|d|days?|t|ticks?)$",
            Pattern.CASE_INSENSITIVE
    );

    private final Map<Type, ConfigCodec<?>> explicit = new ConcurrentHashMap<>();
    private final Map<Type, ConfigCodec<?>> synthesized = new ConcurrentHashMap<>();
    private final List<CodecFactory> factories = new CopyOnWriteArrayList<>();

    private CodecRegistry(boolean registerDefaults) {
        if (registerDefaults) {
            registerBuiltIns();
            registerBuiltInFactories();
        }
    }

    public static CodecRegistry defaults() {
        return new CodecRegistry(true);
    }

    public static CodecRegistry empty() {
        return new CodecRegistry(false);
    }

    public CodecRegistry copy() {
        CodecRegistry clone = new CodecRegistry(false);
        clone.explicit.putAll(this.explicit);
        clone.factories.addAll(this.factories);
        return clone;
    }

    public <T> CodecRegistry register(Class<T> type, ConfigCodec<T> codec) {
        return register((Type) type, codec);
    }

    public <T> CodecRegistry register(TypeRef<T> typeRef, ConfigCodec<T> codec) {
        return register(typeRef.type(), codec);
    }

    public CodecRegistry register(Type type, ConfigCodec<?> codec) {
        explicit.put(type, codec);
        synthesized.clear();
        return this;
    }

    public CodecRegistry registerFactory(CodecFactory factory) {
        factories.add(0, factory);
        synthesized.clear();
        return this;
    }

    public boolean hasExplicit(Type type) {
        return type != null && explicit.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<ConfigCodec<T>> find(Class<T> type) {
        return find((Type) type).map(c -> (ConfigCodec<T>) c);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<ConfigCodec<T>> find(TypeRef<T> typeRef) {
        return find(typeRef.type()).map(c -> (ConfigCodec<T>) c);
    }

    public Optional<ConfigCodec<?>> find(Type type) {
        if (type == null) {
            return Optional.empty();
        }
        ConfigCodec<?> direct = explicit.get(type);
        if (direct != null) {
            return Optional.of(direct);
        }
        ConfigCodec<?> cached = synthesized.get(type);
        if (cached != null) {
            return Optional.of(cached);
        }
        for (CodecFactory factory : factories) {
            ConfigCodec<?> created = factory.create(type, this);
            if (created != null) {
                synthesized.putIfAbsent(type, created);
                return Optional.of(created);
            }
        }
        Class<?> raw = TypeRef.resolveRawType(type);
        if (raw != type) {
            ConfigCodec<?> rawCodec = explicit.get(raw);
            if (rawCodec != null) {
                return Optional.of(rawCodec);
            }
        }
        return Optional.empty();
    }

    private void registerBuiltIns() {
        ConfigCodec<String> stringCodec = ConfigCodec.of(
                val -> {
                    Object raw = val.raw();
                    if (raw == null) {
                        throw new ConfigDecodeException("expected string, got null");
                    }
                    if (raw instanceof Map<?, ?> || raw instanceof List<?>) {
                        throw new ConfigDecodeException("expected scalar string, got " + raw.getClass().getSimpleName());
                    }
                    return String.valueOf(raw);
                },
                (s, ctx) -> ConfigNode.ScalarNode.of(s)
        );
        register(String.class, stringCodec);

        ConfigCodec<Boolean> boolCodec = ConfigCodec.of(
                val -> val.requireBoolean(),
                (b, ctx) -> ConfigNode.ScalarNode.of(b)
        );
        register(Boolean.class, boolCodec);
        register(boolean.class, boolCodec);

        ConfigCodec<Integer> intCodec = ConfigCodec.of(
                ConfigValue::requireInt,
                (i, ctx) -> ConfigNode.ScalarNode.of(i)
        );
        register(Integer.class, intCodec);
        register(int.class, intCodec);

        ConfigCodec<Long> longCodec = ConfigCodec.of(
                ConfigValue::requireLong,
                (l, ctx) -> ConfigNode.ScalarNode.of(l)
        );
        register(Long.class, longCodec);
        register(long.class, longCodec);

        ConfigCodec<Short> shortCodec = ConfigCodec.of(
                val -> val.requireIntegral().shortValueExact(),
                (s, ctx) -> ConfigNode.ScalarNode.of(s)
        );
        register(Short.class, shortCodec);
        register(short.class, shortCodec);

        ConfigCodec<Byte> byteCodec = ConfigCodec.of(
                val -> val.requireIntegral().byteValueExact(),
                (b, ctx) -> ConfigNode.ScalarNode.of(b)
        );
        register(Byte.class, byteCodec);
        register(byte.class, byteCodec);

        ConfigCodec<Double> doubleCodec = ConfigCodec.of(
                ConfigValue::requireDouble,
                (d, ctx) -> ConfigNode.ScalarNode.of(d)
        );
        register(Double.class, doubleCodec);
        register(double.class, doubleCodec);

        ConfigCodec<Float> floatCodec = ConfigCodec.of(
                val -> (float) val.requireDouble(),
                (f, ctx) -> ConfigNode.ScalarNode.of(f)
        );
        register(Float.class, floatCodec);
        register(float.class, floatCodec);

        register(BigInteger.class, ConfigCodec.of(
                ConfigValue::requireIntegral,
                (bi, ctx) -> new ConfigNode.ScalarNode(bi.toString(), ScalarStyle.SINGLE_QUOTED, SourceSpan.UNKNOWN, NodeComments.EMPTY)
        ));
        register(BigDecimal.class, ConfigCodec.of(
                val -> {
                    Object raw = val.raw();
                    if (raw == null) {
                        throw new ConfigDecodeException("expected decimal number");
                    }
                    try {
                        return new BigDecimal(String.valueOf(raw).trim());
                    } catch (NumberFormatException ex) {
                        throw new ConfigDecodeException("invalid BigDecimal '" + raw + "'", ex);
                    }
                },
                (bd, ctx) -> ConfigNode.ScalarNode.of(bd.toPlainString())
        ));
        register(UUID.class, ConfigCodec.of(
                val -> {
                    String s = requireScalarString(val, "UUID");
                    try {
                        return UUID.fromString(s.trim());
                    } catch (IllegalArgumentException ex) {
                        throw new ConfigDecodeException("invalid UUID '" + s + "'", ex);
                    }
                },
                (u, ctx) -> ConfigNode.ScalarNode.of(u.toString())
        ));
        register(Duration.class, ConfigCodec.of(
                CodecRegistry::decodeDuration,
                (d, ctx) -> ConfigNode.ScalarNode.of(formatDuration(d))
        ));
        register(Pattern.class, ConfigCodec.of(
                val -> {
                    String s = requireScalarString(val, "regex pattern");
                    try {
                        return Pattern.compile(s);
                    } catch (PatternSyntaxException ex) {
                        throw new ConfigDecodeException("invalid regex pattern '" + s + "': " + ex.getDescription(), ex);
                    }
                },
                (p, ctx) -> ConfigNode.ScalarNode.of(p.pattern())
        ));
        register(Path.class, ConfigCodec.of(
                val -> {
                    String s = requireScalarString(val, "file path");
                    try {
                        return Path.of(s.trim());
                    } catch (InvalidPathException ex) {
                        throw new ConfigDecodeException("invalid path '" + s + "'", ex);
                    }
                },
                (p, ctx) -> ConfigNode.ScalarNode.of(p.toString().replace('\\', '/'))
        ));
        register(URI.class, ConfigCodec.of(
                val -> {
                    String s = requireScalarString(val, "URI");
                    try {
                        return URI.create(s.trim());
                    } catch (IllegalArgumentException ex) {
                        throw new ConfigDecodeException("invalid URI '" + s + "'", ex);
                    }
                },
                (u, ctx) -> ConfigNode.ScalarNode.of(u.toString())
        ));
        register(ConfigSection.class, ConfigCodec.of(
                ConfigValue::asSection,
                (sec, ctx) -> sec.node()
        ));
        register(ConfigNode.class, ConfigCodec.of(
                ConfigValue::node,
                (node, ctx) -> node
        ));
        register(Object.class, ConfigCodec.of(
                ConfigValue::raw,
                (obj, ctx) -> ctx.encode(obj, obj != null ? obj.getClass() : Object.class)
        ));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerBuiltInFactories() {
        // Enum factory (case-insensitive & hyphen-to-underscore tolerant)
        factories.add((type, reg) -> {
            Class<?> raw = TypeRef.resolveRawType(type);
            if (!raw.isEnum()) {
                return null;
            }
            Class<? extends Enum> enumClass = (Class<? extends Enum>) raw;
            return ConfigCodec.of(
                    val -> parseEnum(val, enumClass),
                    (e, ctx) -> ConfigNode.ScalarNode.of(((Enum<?>) e).name())
            );
        });

        // Optional<T> factory
        factories.add((type, reg) -> {
            Class<?> raw = TypeRef.resolveRawType(type);
            if (raw != Optional.class) {
                return null;
            }
            Type innerType = TypeRef.typeArgument(type, 0);
            ConfigCodec<Object> innerCodec = innerType != null
                    ? (ConfigCodec<Object>) reg.find(innerType).orElse(null)
                    : null;
            return ConfigCodec.of(
                    val -> {
                        if (!val.isPresent() || val.isNull()) {
                            return Optional.empty();
                        }
                        if (innerCodec == null) {
                            return Optional.ofNullable(val.raw());
                        }
                        return Optional.ofNullable(innerCodec.decode(val));
                    },
                    (opt, ctx) -> {
                        Optional<?> o = (Optional<?>) opt;
                        return o.isEmpty()
                                ? ConfigNode.NullNode.INSTANCE
                                : ctx.encode(o.get(), innerType);
                    }
            );
        });

        // List<T> / Set<T> / Collection<T> factory
        factories.add((type, reg) -> {
            Class<?> raw = TypeRef.resolveRawType(type);
            if (!Collection.class.isAssignableFrom(raw)) {
                return null;
            }
            boolean isSet = Set.class.isAssignableFrom(raw);
            Type elemType = TypeRef.typeArgument(type, 0);
            return ConfigCodec.of(
                    val -> {
                        if (!(val.node() instanceof ConfigNode.ListNode listNode)) {
                            throw new ConfigDecodeException("expected list, got " + val.nodeKindName());
                        }
                        Collection<Object> out = isSet
                                ? new LinkedHashSet<>(listNode.elements().size())
                                : new ArrayList<>(listNode.elements().size());
                        ConfigCodec<Object> elemCodec = elemType != null
                                ? (ConfigCodec<Object>) reg.find(elemType).orElse(null)
                                : null;
                        List<ConfigNode> elems = listNode.elements();
                        for (int i = 0; i < elems.size(); i++) {
                            ConfigValue itemVal = val.child("[" + i + "]", elems.get(i));
                            if (elemCodec != null) {
                                out.add(elemCodec.decode(itemVal));
                            } else {
                                out.add(itemVal.raw());
                            }
                        }
                        return isSet
                                ? Collections.unmodifiableSet((Set<Object>) out)
                                : Collections.unmodifiableList((List<Object>) out);
                    },
                    (col, ctx) -> {
                        Collection<?> c = (Collection<?>) col;
                        List<ConfigNode> nodes = new ArrayList<>(c.size());
                        for (Object item : c) {
                            nodes.add(ctx.encode(item, elemType));
                        }
                        return ConfigNode.ListNode.of(nodes);
                    }
            );
        });

        // Map<K, V> factory
        factories.add((type, reg) -> {
            Class<?> raw = TypeRef.resolveRawType(type);
            if (!Map.class.isAssignableFrom(raw)) {
                return null;
            }
            Type keyType = TypeRef.typeArgument(type, 0);
            Type valType = TypeRef.typeArgument(type, 1);
            return ConfigCodec.of(
                    val -> {
                        if (!(val.node() instanceof ConfigNode.MapNode mapNode)) {
                            throw new ConfigDecodeException("expected mapping, got " + val.nodeKindName());
                        }
                        ConfigCodec<Object> keyCodec = keyType != null
                                ? (ConfigCodec<Object>) reg.find(keyType).orElse(null)
                                : null;
                        ConfigCodec<Object> valCodec = valType != null
                                ? (ConfigCodec<Object>) reg.find(valType).orElse(null)
                                : null;
                        Map<Object, Object> out = new LinkedHashMap<>(mapNode.entries().size());
                        for (ConfigNode.MapEntry entry : mapNode.entries().values()) {
                            ConfigValue kVal = new ConfigValue(
                                    val.source(),
                                    val.path(),
                                    ConfigNode.ScalarNode.of(entry.key()).withSpan(entry.keySpan()),
                                    val.diagnostics(),
                                    reg
                            );
                            Object decodedKey = keyCodec != null ? keyCodec.decode(kVal) : entry.key();
                            ConfigValue vVal = val.childKey(entry.key(), entry.value());
                            Object decodedVal = valCodec != null ? valCodec.decode(vVal) : entry.value().rawValue();
                            out.put(decodedKey, decodedVal);
                        }
                        return Collections.unmodifiableMap(out);
                    },
                    (mapObj, ctx) -> {
                        Map<?, ?> m = (Map<?, ?>) mapObj;
                        List<ConfigNode.MapEntry> entries = new ArrayList<>(m.size());
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            if (e.getKey() == null) {
                                continue;
                            }
                            String kStr = e.getKey() instanceof Enum<?> en ? en.name() : String.valueOf(e.getKey());
                            entries.add(ConfigNode.MapEntry.of(kStr, ctx.encode(e.getValue(), valType)));
                        }
                        return ConfigNode.MapNode.ofEntries(entries);
                    }
            );
        });
    }

    private static String requireScalarString(ConfigValue val, String label) {
        Object raw = val.raw();
        if (raw == null || raw instanceof Map<?, ?> || raw instanceof List<?>) {
            throw new ConfigDecodeException("expected " + label + " scalar string");
        }
        String s = String.valueOf(raw);
        if (s.isBlank()) {
            throw new ConfigDecodeException("blank " + label);
        }
        return s;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends Enum<E>> E parseEnum(ConfigValue value, Class<E> enumType) {
        Object raw = value.raw();
        if (raw == null) {
            throw new ConfigDecodeException("missing enum value for " + enumType.getSimpleName());
        }
        String token = String.valueOf(raw).trim();
        String normalized = token.replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException ex) {
            throw new ConfigDecodeException(
                    "invalid " + enumType.getSimpleName() + " '" + raw + "', expected one of "
                            + Arrays.toString(enumType.getEnumConstants())
            );
        }
    }

    private static Duration decodeDuration(ConfigValue val) {
        Object raw = val.raw();
        if (raw == null) {
            throw new ConfigDecodeException("missing duration value");
        }
        if (raw instanceof Number n) {
            long ms = n.longValue();
            return Duration.ofMillis(ms);
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            throw new ConfigDecodeException("blank duration");
        }
        if (text.startsWith("P") || text.startsWith("p") || text.startsWith("-P")) {
            try {
                return Duration.parse(text.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException ex) {
                throw new ConfigDecodeException("invalid ISO-8601 duration '" + text + "'", ex);
            }
        }
        Matcher matcher = DURATION_TOKEN.matcher(text);
        if (matcher.matches()) {
            double amount = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            long millis = switch (unit) {
                case "ms", "milli", "millis" -> Math.round(amount);
                case "s", "sec", "second", "seconds" -> Math.round(amount * 1_000.0);
                case "m", "min", "minute", "minutes" -> Math.round(amount * 60_000.0);
                case "h", "hr", "hour", "hours" -> Math.round(amount * 3_600_000.0);
                case "d", "day", "days" -> Math.round(amount * 86_400_000.0);
                case "t", "tick", "ticks" -> Math.round(amount * 50.0);
                default -> throw new ConfigDecodeException("unsupported duration unit '" + unit + "'");
            };
            return Duration.ofMillis(millis);
        }
        try {
            return Duration.ofMillis(Long.parseLong(text));
        } catch (NumberFormatException ex) {
            throw new ConfigDecodeException("invalid duration '" + text + "' (e.g. 500ms, 10s, 5m, 20t, PT10S)");
        }
    }

    private static String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms == 0) {
            return "0ms";
        }
        if (ms % 86_400_000L == 0) {
            return (ms / 86_400_000L) + "d";
        }
        if (ms % 3_600_000L == 0) {
            return (ms / 3_600_000L) + "h";
        }
        if (ms % 60_000L == 0) {
            return (ms / 60_000L) + "m";
        }
        if (ms % 1_000L == 0) {
            return (ms / 1_000L) + "s";
        }
        return ms + "ms";
    }
}
