package com.aether.config.view;

import com.aether.config.codec.CodecRegistry;
import com.aether.config.codec.ConfigCodec;
import com.aether.config.codec.TypeRef;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.diagnostic.SpellingSuggester;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.SourceSpan;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable path-aware view over a {@link ConfigNode.MapNode} supporting dot-paths, exact keys,
 * source-attributed diagnostics, unknown-key spelling hints, and typed codecs.
 */
public final class ConfigSection {

    private final String source;
    private final String basePath;
    private final ConfigNode.MapNode node;
    private final ConfigDiagnostics diagnostics;
    private final CodecRegistry codecs;

    private ConfigSection(
            String source,
            String basePath,
            ConfigNode.MapNode node,
            ConfigDiagnostics diagnostics,
            CodecRegistry codecs
    ) {
        this.source = source;
        this.basePath = basePath;
        this.node = node;
        this.diagnostics = diagnostics;
        this.codecs = codecs;
    }

    public static ConfigSection empty(String source, ConfigDiagnostics diagnostics, CodecRegistry codecs) {
        return empty(source, "", diagnostics, codecs);
    }

    public static ConfigSection empty(String source, String basePath, ConfigDiagnostics diagnostics, CodecRegistry codecs) {
        return new ConfigSection(source, basePath, ConfigNode.MapNode.EMPTY, diagnostics, codecs);
    }

    public static ConfigSection ofNode(
            String source,
            String basePath,
            @Nullable ConfigNode.MapNode mapNode,
            ConfigDiagnostics diagnostics,
            CodecRegistry codecs
    ) {
        if (mapNode == null || mapNode.isEmpty()) {
            return new ConfigSection(source, basePath, ConfigNode.MapNode.EMPTY, diagnostics, codecs);
        }
        return new ConfigSection(source, basePath, mapNode, diagnostics, codecs);
    }

    public static ConfigSection ofMap(
            String source,
            @Nullable Map<?, ?> map,
            ConfigDiagnostics diagnostics,
            CodecRegistry codecs
    ) {
        return ofMap(source, "", map, diagnostics, codecs);
    }

    public static ConfigSection ofMap(
            String source,
            String basePath,
            @Nullable Map<?, ?> map,
            ConfigDiagnostics diagnostics,
            CodecRegistry codecs
    ) {
        if (map == null || map.isEmpty()) {
            return empty(source, basePath, diagnostics, codecs);
        }
        ConfigNode converted = ConfigDocument.toNode(map);
        return converted instanceof ConfigNode.MapNode mn
                ? ofNode(source, basePath, mn, diagnostics, codecs)
                : empty(source, basePath, diagnostics, codecs);
    }

    public String source() {
        return source;
    }

    public String basePath() {
        return basePath;
    }

    public ConfigNode.MapNode node() {
        return node;
    }

    public SourceSpan span() {
        return node.span();
    }

    public ConfigDiagnostics diagnostics() {
        return diagnostics;
    }

    public CodecRegistry codecs() {
        return codecs;
    }

    public boolean isEmpty() {
        return node.isEmpty();
    }

    public Set<String> keys() {
        return node.entries().keySet();
    }

    public boolean has(String path) {
        ConfigNode resolved = resolveNode(path);
        return resolved != null && !(resolved instanceof ConfigNode.NullNode);
    }

    public boolean hasExact(String key) {
        ConfigNode.MapEntry entry = node.entry(key);
        return entry != null && !(entry.value() instanceof ConfigNode.NullNode);
    }

    public boolean isSection(String path) {
        return resolveNode(path) instanceof ConfigNode.MapNode;
    }

    public boolean isList(String path) {
        return resolveNode(path) instanceof ConfigNode.ListNode;
    }

    public String fullPath(String relativePath) {
        if (basePath.isEmpty()) {
            return relativePath;
        }
        if (relativePath.isEmpty()) {
            return basePath;
        }
        return basePath + "." + relativePath;
    }

    public ConfigSection section(String path) {
        ConfigNode raw = resolveNode(path);
        String resolvedPath = fullPath(path);
        if (raw == null || raw instanceof ConfigNode.NullNode) {
            return empty(source, resolvedPath, diagnostics, codecs);
        }
        if (raw instanceof ConfigNode.MapNode mapNode) {
            return ofNode(source, resolvedPath, mapNode, diagnostics, codecs);
        }
        diagnostics.warn(DiagnosticCategory.TYPE, raw.span(), resolvedPath,
                "Expected mapping section, got " + raw.getClass().getSimpleName() + ".");
        return empty(source, resolvedPath, diagnostics, codecs);
    }

    public Optional<ConfigSection> optionalSection(String path) {
        ConfigNode raw = resolveNode(path);
        if (raw instanceof ConfigNode.MapNode mapNode) {
            return Optional.of(ofNode(source, fullPath(path), mapNode, diagnostics, codecs));
        }
        return Optional.empty();
    }

    public ConfigValue value(String path) {
        return new ConfigValue(source, fullPath(path), resolveNode(path), diagnostics, codecs);
    }

    public ConfigValue valueExact(String exactKey) {
        return new ConfigValue(source, fullPath(exactKey), node.child(exactKey), diagnostics, codecs);
    }

    public @Nullable String getString(String path, @Nullable String defaultValue) {
        return value(path).asString(defaultValue);
    }

    public @Nullable String getNonBlankString(String path) {
        return value(path).asNonBlankString();
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        return value(path).asBoolean(defaultValue);
    }

    public int getInt(String path, int defaultValue) {
        return value(path).asInt(defaultValue);
    }

    public int getInt(String path, int defaultValue, int min, int max) {
        return value(path).asInt(defaultValue, min, max);
    }

    public long getLong(String path, long defaultValue) {
        return value(path).asLong(defaultValue);
    }

    public long getLong(String path, long defaultValue, long min, long max) {
        return value(path).asLong(defaultValue, min, max);
    }

    public double getDouble(String path, double defaultValue) {
        return value(path).asDouble(defaultValue);
    }

    public double getDouble(String path, double defaultValue, double min, double max) {
        return value(path).asDouble(defaultValue, min, max);
    }

    public @Nullable BigInteger getBigInteger(String path, @Nullable BigInteger defaultValue) {
        return value(path).asBigInteger(defaultValue);
    }

    public @Nullable BigDecimal getBigDecimal(String path, @Nullable BigDecimal defaultValue) {
        return value(path).asBigDecimal(defaultValue);
    }

    public @Nullable Duration getDuration(String path, @Nullable Duration defaultValue) {
        return value(path).asDuration(defaultValue);
    }

    public <E extends Enum<E>> @Nullable E getEnum(String path, Class<E> enumType, @Nullable E defaultValue) {
        return value(path).asEnum(enumType, defaultValue);
    }

    public List<String> getStringList(String path) {
        return value(path).asStringList();
    }

    public List<ConfigSection> getSectionList(String path) {
        return value(path).asSectionList();
    }

    public <T> @Nullable T decode(String path, Class<T> type, @Nullable T defaultValue) {
        return value(path).decode(type, defaultValue);
    }

    public <T> @Nullable T decode(String path, TypeRef<T> typeRef, @Nullable T defaultValue) {
        return value(path).decode(typeRef, defaultValue);
    }

    public <T> @Nullable T decode(String path, ConfigCodec<T> codec, @Nullable T defaultValue) {
        return value(path).decode(codec, defaultValue);
    }

    public <T> List<T> decodeList(String path, ConfigCodec<T> codec) {
        ConfigNode raw = resolveNode(path);
        String resolvedPath = fullPath(path);
        if (raw == null || raw instanceof ConfigNode.NullNode) {
            return List.of();
        }
        if (!(raw instanceof ConfigNode.ListNode listNode)) {
            diagnostics.warn(DiagnosticCategory.TYPE, raw.span(), resolvedPath,
                    "Expected list, got " + raw.getClass().getSimpleName() + ".");
            return List.of();
        }
        List<ConfigNode> elems = listNode.elements();
        List<T> result = new ArrayList<>(elems.size());
        for (int i = 0; i < elems.size(); i++) {
            ConfigValue elemValue = new ConfigValue(source, resolvedPath + "[" + i + "]", elems.get(i), diagnostics, codecs);
            T decoded = elemValue.decode(codec, null);
            if (decoded != null) {
                result.add(decoded);
            }
        }
        return List.copyOf(result);
    }

    public <V> Map<String, V> decodeMap(String path, ConfigCodec<V> valueCodec) {
        ConfigSection sub = section(path);
        if (sub.isEmpty()) {
            return Map.of();
        }
        Map<String, V> result = new LinkedHashMap<>(sub.keys().size());
        for (ConfigNode.MapEntry entry : sub.node().entries().values()) {
            ConfigValue elemVal = new ConfigValue(source, sub.fullPath(entry.key()), entry.value(), diagnostics, codecs);
            V decoded = elemVal.decode(valueCodec, null);
            if (decoded != null) {
                result.put(entry.key(), decoded);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public void warnUnknownKeys(Set<String> allowedKeys) {
        for (ConfigNode.MapEntry entry : node.entries().values()) {
            String key = entry.key();
            if (!allowedKeys.contains(key)) {
                String suggestion = SpellingSuggester.suggest(key, allowedKeys);
                String hint = suggestion != null ? "Did you mean '" + suggestion + "'?" : null;
                diagnostics.warn(
                        DiagnosticCategory.SCHEMA,
                        entry.keySpan().isKnown() ? entry.keySpan() : entry.value().span(),
                        fullPath(key),
                        "Unknown configuration key '" + key + "'.",
                        hint
                );
            }
        }
    }

    private @Nullable ConfigNode resolveNode(String path) {
        if (path == null || path.isEmpty() || node.isEmpty()) {
            return null;
        }
        // Fast exact match first (supports keys containing literal dots like "1.21.4")
        ConfigNode direct = node.child(path);
        if (direct != null) {
            return direct;
        }
        List<String> segments = ConfigDocument.splitPath(path);
        ConfigNode current = node;
        for (String seg : segments) {
            if (!(current instanceof ConfigNode.MapNode mapNode)) {
                return null;
            }
            current = mapNode.child(seg);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
