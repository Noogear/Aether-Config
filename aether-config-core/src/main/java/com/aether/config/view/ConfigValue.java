package com.aether.config.view;

import com.aether.config.codec.CodecRegistry;
import com.aether.config.codec.ConfigCodec;
import com.aether.config.codec.TypeRef;
import com.aether.config.diagnostic.ConfigDecodeException;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.SourceSpan;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Strongly-typed view over a single {@link ConfigNode} with source-span attributed diagnostics.
 */
public final class ConfigValue {

    private final String source;
    private final String path;
    private final ConfigNode node;
    private final ConfigDiagnostics diagnostics;
    private final CodecRegistry codecs;

    public ConfigValue(
            String source,
            String path,
            @Nullable ConfigNode node,
            ConfigDiagnostics diagnostics,
            CodecRegistry codecs
    ) {
        this.source = source;
        this.path = path;
        this.node = node != null ? node : ConfigNode.NullNode.INSTANCE;
        this.diagnostics = diagnostics;
        this.codecs = codecs;
    }

    public String source() {
        return source;
    }

    public String path() {
        return path;
    }

    public ConfigNode node() {
        return node;
    }

    public SourceSpan span() {
        return node.span();
    }

    public boolean isPresent() {
        return !(node instanceof ConfigNode.NullNode) || node.span().isKnown();
    }

    public boolean isNull() {
        return node instanceof ConfigNode.NullNode;
    }

    public @Nullable Object raw() {
        return node.rawValue();
    }

    public ConfigDiagnostics diagnostics() {
        return diagnostics;
    }

    public CodecRegistry codecs() {
        return codecs;
    }

    public ConfigValue withDiagnostics(ConfigDiagnostics targetDiagnostics) {
        return new ConfigValue(source, path, node, targetDiagnostics, codecs);
    }

    public ConfigValue child(String suffix, ConfigNode childNode) {
        return new ConfigValue(source, path.isEmpty() ? suffix : path + suffix, childNode, diagnostics, codecs);
    }

    public ConfigValue childKey(String key, ConfigNode childNode) {
        return new ConfigValue(source, path.isEmpty() ? key : path + "." + key, childNode, diagnostics, codecs);
    }

    public String nodeKindName() {
        return switch (node) {
            case ConfigNode.NullNode ignored -> "null";
            case ConfigNode.ScalarNode s -> s.value().getClass().getSimpleName();
            case ConfigNode.ListNode ignored -> "List";
            case ConfigNode.MapNode ignored -> "Mapping";
        };
    }

    public @Nullable String asString(@Nullable String defaultValue) {
        Object raw = raw();
        if (raw == null) {
            return defaultValue;
        }
        if (node instanceof ConfigNode.MapNode || node instanceof ConfigNode.ListNode) {
            diagnostics.warn(DiagnosticCategory.TYPE, span(), path,
                    "Expected scalar string, got " + nodeKindName() + "; using default.");
            return defaultValue;
        }
        return String.valueOf(raw);
    }

    public @Nullable String asNonBlankString() {
        String s = asString(null);
        if (s == null || s.isBlank()) {
            diagnostics.error(DiagnosticCategory.VALIDATION, span(), path, "Missing or blank required string.");
            return null;
        }
        return s.trim();
    }

    public boolean asBoolean(boolean defaultValue) {
        if (isNull()) {
            return defaultValue;
        }
        try {
            return requireBoolean();
        } catch (ConfigDecodeException ex) {
            return warnAndFallback(ex, defaultValue);
        }
    }

    public boolean requireBoolean() {
        Object raw = raw();
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
        }
        throw new ConfigDecodeException("invalid boolean '" + raw + "'");
    }

    public int asInt(int defaultValue) {
        return asInt(defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public int asInt(int defaultValue, int min, int max) {
        if (isNull()) {
            return defaultValue;
        }
        try {
            int parsed = requireInt();
            if (parsed < min || parsed > max) {
                int clamped = Math.max(min, Math.min(max, parsed));
                warnClamped(parsed, min, max, clamped);
                return clamped;
            }
            return parsed;
        } catch (ConfigDecodeException ex) {
            return warnAndFallback(ex, defaultValue);
        }
    }

    public int requireInt() {
        try {
            return requireIntegral().intValueExact();
        } catch (ArithmeticException ex) {
            throw new ConfigDecodeException("integer out of 32-bit range '" + raw() + "'", ex);
        }
    }

    public long asLong(long defaultValue) {
        return asLong(defaultValue, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public long asLong(long defaultValue, long min, long max) {
        if (isNull()) {
            return defaultValue;
        }
        try {
            long parsed = requireLong();
            if (parsed < min || parsed > max) {
                long clamped = Math.max(min, Math.min(max, parsed));
                warnClamped(parsed, min, max, clamped);
                return clamped;
            }
            return parsed;
        } catch (ConfigDecodeException ex) {
            return warnAndFallback(ex, defaultValue);
        }
    }

    public long requireLong() {
        try {
            return requireIntegral().longValueExact();
        } catch (ArithmeticException ex) {
            throw new ConfigDecodeException("integer out of 64-bit range '" + raw() + "'", ex);
        }
    }

    public double asDouble(double defaultValue) {
        return asDouble(defaultValue, -Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public double asDouble(double defaultValue, double min, double max) {
        if (isNull()) {
            return defaultValue;
        }
        try {
            double parsed = requireDouble();
            if (parsed < min || parsed > max) {
                double clamped = Math.max(min, Math.min(max, parsed));
                warnClamped(parsed, min, max, clamped);
                return clamped;
            }
            return parsed;
        } catch (ConfigDecodeException ex) {
            return warnAndFallback(ex, defaultValue);
        }
    }

    public double requireDouble() {
        Object raw = raw();
        double parsed;
        if (raw instanceof Number n) {
            parsed = n.doubleValue();
        } else if (raw != null && !(node instanceof ConfigNode.MapNode || node instanceof ConfigNode.ListNode)) {
            try {
                parsed = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                throw new ConfigDecodeException("invalid number '" + raw + "'", ex);
            }
        } else {
            throw new ConfigDecodeException("expected number, got " + nodeKindName());
        }
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            throw new ConfigDecodeException("non-finite number '" + raw + "'");
        }
        return parsed;
    }

    public BigInteger requireIntegral() {
        Object value = raw();
        if (value == null || node instanceof ConfigNode.MapNode || node instanceof ConfigNode.ListNode) {
            throw new ConfigDecodeException("expected integer, got " + nodeKindName());
        }
        try {
            if (value instanceof BigInteger bi) {
                return bi;
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return BigInteger.valueOf(((Number) value).longValue());
            }
            return new BigDecimal(String.valueOf(value).trim()).toBigIntegerExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new ConfigDecodeException("invalid integer '" + value + "'", ex);
        }
    }

    public @Nullable BigInteger asBigInteger(@Nullable BigInteger defaultValue) {
        if (isNull()) {
            return defaultValue;
        }
        try {
            return requireIntegral();
        } catch (ConfigDecodeException ex) {
            diagnostics.error(DiagnosticCategory.TYPE, span(), path, ex.getMessage());
            return defaultValue;
        }
    }

    public @Nullable BigDecimal asBigDecimal(@Nullable BigDecimal defaultValue) {
        return decode(BigDecimal.class, defaultValue);
    }

    public @Nullable Duration asDuration(@Nullable Duration defaultValue) {
        return decode(Duration.class, defaultValue);
    }

    public <E extends Enum<E>> @Nullable E asEnum(Class<E> enumType, @Nullable E defaultValue) {
        if (isNull()) {
            return defaultValue;
        }
        String rawText = String.valueOf(raw()).trim();
        String token = rawText.replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(enumType, token);
        } catch (IllegalArgumentException ex) {
            diagnostics.error(
                    DiagnosticCategory.TYPE,
                    span(),
                    path,
                    "Invalid " + enumType.getSimpleName() + " '" + rawText + "', expected one of "
                            + Arrays.toString(enumType.getEnumConstants()) + "."
            );
            return defaultValue;
        }
    }

    public List<String> asStringList() {
        if (isNull()) {
            return List.of();
        }
        if (!(node instanceof ConfigNode.ListNode listNode)) {
            diagnostics.warn(DiagnosticCategory.TYPE, span(), path,
                    "Expected list of strings, got " + nodeKindName() + ".");
            return List.of();
        }
        List<String> result = new ArrayList<>(listNode.elements().size());
        for (ConfigNode entry : listNode.elements()) {
            Object raw = entry.rawValue();
            if (raw != null) {
                result.add(String.valueOf(raw));
            }
        }
        return List.copyOf(result);
    }

    public List<ConfigSection> asSectionList() {
        if (isNull()) {
            return List.of();
        }
        if (!(node instanceof ConfigNode.ListNode listNode)) {
            diagnostics.warn(DiagnosticCategory.TYPE, span(), path,
                    "Expected list of sections, got " + nodeKindName() + ".");
            return List.of();
        }
        List<ConfigNode> elems = listNode.elements();
        List<ConfigSection> result = new ArrayList<>(elems.size());
        for (int i = 0; i < elems.size(); i++) {
            ConfigNode elem = elems.get(i);
            String elemPath = path + "[" + i + "]";
            if (elem instanceof ConfigNode.MapNode mapNode) {
                result.add(ConfigSection.ofNode(source, elemPath, mapNode, diagnostics, codecs));
            } else {
                diagnostics.warn(DiagnosticCategory.TYPE, elem.span(), elemPath,
                        "Expected mapping section, got " + elem.getClass().getSimpleName() + "; skipping.");
            }
        }
        return List.copyOf(result);
    }

    public ConfigSection asSection() {
        if (isNull()) {
            return ConfigSection.empty(source, path, diagnostics, codecs);
        }
        if (node instanceof ConfigNode.MapNode mapNode) {
            return ConfigSection.ofNode(source, path, mapNode, diagnostics, codecs);
        }
        diagnostics.warn(DiagnosticCategory.TYPE, span(), path,
                "Expected mapping section, got " + nodeKindName() + ".");
        return ConfigSection.empty(source, path, diagnostics, codecs);
    }

    public <T> @Nullable T decode(Class<T> type, @Nullable T defaultValue) {
        return decode(TypeRef.of(type), defaultValue);
    }

    @SuppressWarnings("unchecked")
    public <T> @Nullable T decode(TypeRef<T> typeRef, @Nullable T defaultValue) {
        if (isNull()) {
            return defaultValue;
        }
        var codecOpt = codecs.find(typeRef);
        if (codecOpt.isEmpty()) {
            diagnostics.error(DiagnosticCategory.TYPE, span(), path,
                    "No ConfigCodec registered for type " + typeRef.type().getTypeName() + ".");
            return defaultValue;
        }
        return decode((ConfigCodec<T>) codecOpt.get(), defaultValue);
    }

    public <T> @Nullable T decode(ConfigCodec<T> codec, @Nullable T defaultValue) {
        if (isNull()) {
            return defaultValue;
        }
        try {
            T decoded = codec.decode(this);
            return decoded != null ? decoded : defaultValue;
        } catch (ConfigDecodeException ex) {
            diagnostics.error(DiagnosticCategory.TYPE, span(), path, ex.getMessage());
            return defaultValue;
        }
    }

    private void warnClamped(Object parsed, Object min, Object max, Object clamped) {
        diagnostics.warn(DiagnosticCategory.VALIDATION, span(), path,
                "Value " + parsed + " out of range [" + min + ", " + max + "], clamped to " + clamped + ".");
    }

    private <T> T warnAndFallback(ConfigDecodeException ex, T fallback) {
        diagnostics.warn(DiagnosticCategory.TYPE, span(), path, ex.getMessage() + ", using default " + fallback + ".");
        return fallback;
    }
}
