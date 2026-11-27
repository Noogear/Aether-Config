package com.aether.config.bind;

import com.aether.config.annotation.Alias;
import com.aether.config.annotation.Check;
import com.aether.config.annotation.Header;
import com.aether.config.annotation.PostLoad;
import com.aether.config.annotation.PreLoad;
import com.aether.config.annotation.Range;
import com.aether.config.annotation.Template;
import com.aether.config.annotation.Version;
import com.aether.config.codec.CodecContext;
import com.aether.config.codec.CodecRegistry;
import com.aether.config.codec.ConfigCodec;
import com.aether.config.codec.TypeRef;
import com.aether.config.diagnostic.ConfigDecodeException;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.diagnostic.SpellingSuggester;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.SourceSpan;
import com.aether.config.view.ConfigSection;
import com.aether.config.view.ConfigValue;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Bidirectional binder between {@link ConfigSection}/{@link ConfigDocument} and Java {@code record} or POJO schemas.
 */
public final class ObjectBinder {

    private static final Map<Class<?>, Check.ConfigValidator<?>> VALIDATOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

    private final CodecRegistry baseCodecs;
    private final CodecRegistry codecs;
    private final BindOptions options;

    public ObjectBinder(CodecRegistry baseCodecs, BindOptions options) {
        this.options = options != null ? options : BindOptions.DEFAULT;
        this.baseCodecs = baseCodecs != null ? baseCodecs : CodecRegistry.defaults();
        CodecRegistry enhanced = this.baseCodecs.copy();
        enhanced.registerFactory((type, registry) -> {
            Class<?> raw = TypeRef.resolveRawType(type);
            if (!isBindableObjectType(raw)) {
                return null;
            }
            return ConfigCodec.of(
                    val -> bind(raw, val.asSection(), null, null),
                    (obj, ctx) -> encodeToNode(obj)
            );
        });
        this.codecs = enhanced;
    }

    public CodecRegistry codecs() {
        return codecs;
    }

    public <T> T bind(
            Class<T> type,
            ConfigSection section,
            @Nullable ConfigDocument patchDoc,
            @Nullable Object hookContext
    ) {
        List<PropertySchema> props = PropertySchema.forType(type);
        handleUnknownKeys(type, props, section, patchDoc);
        if (patchDoc != null && section.basePath().isEmpty()) {
            applyHeaderIfAbsent(type, patchDoc);
        }

        T instance;
        if (type.isRecord()) {
            Object[] args = new Object[props.size()];
            for (int i = 0; i < props.size(); i++) {
                args[i] = bindProperty(props.get(i), null, section, patchDoc, hookContext);
            }
            try {
                @SuppressWarnings("unchecked")
                T created = (T) PropertySchema.recordCanonicalConstructor(type).newInstance(args);
                instance = created;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to instantiate record " + type.getName(), e);
            }
        } else {
            instance = instantiateNoArg(type);
            runHooks(instance, PreLoad.class, hookContext, section.diagnostics(), section.span());
            for (PropertySchema prop : props) {
                Object bound = bindProperty(prop, prop.get(instance), section, patchDoc, hookContext);
                prop.set(instance, bound);
            }
        }

        runHooks(instance, PostLoad.class, hookContext, section.diagnostics(), section.span());
        return instance;
    }

    public <T> ConfigNode.MapNode encodeDocumentNode(T instance) {
        ConfigDocument doc = ConfigDocument.empty("<generated>");
        encodeIntoDocument(instance, doc, "");
        return doc.toNode();
    }

    public <T> void syncCommentsAndDefaults(T instance, ConfigDocument doc) {
        applyHeaderIfAbsent(instance.getClass(), doc);
        syncMetaComments(instance.getClass(), doc, "");
    }

    private void syncMetaComments(Class<?> type, ConfigDocument doc, String prefix) {
        for (PropertySchema prop : PropertySchema.forType(type)) {
            String key = prop.resolveKey(options.naming());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (!doc.has(path)) {
                continue;
            }
            attachCommentsIfAbsent(prop, path, doc);
            if (isNestedBindable(prop.rawType())) {
                syncMetaComments(prop.rawType(), doc, path);
            }
        }
    }

    private void encodeIntoDocument(Object instance, ConfigDocument doc, String prefix) {
        if (prefix.isEmpty()) {
            applyHeaderIfAbsent(instance.getClass(), doc);
        }
        CodecContext ctx = new CodecContext(codecs);
        for (PropertySchema prop : PropertySchema.forType(instance.getClass())) {
            String key = prop.resolveKey(options.naming());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object val = prop.get(instance);
            if (val == null) {
                continue;
            }
            if (isNestedBindable(prop.rawType())) {
                encodeIntoDocument(val, doc, path);
                if (!doc.has(path)) {
                    doc.set(path, ConfigNode.MapNode.EMPTY);
                }
            } else {
                doc.set(path, ctx.encode(val, prop.genericType()));
            }
            if (prop.comment() != null) {
                doc.setBlockComments(path, List.of(prop.comment().value()));
            }
            if (prop.inline() != null) {
                doc.setInlineComments(path, List.of(prop.inline().value()));
            }
        }
    }

    private ConfigNode encodeToNode(Object instance) {
        ConfigDocument doc = ConfigDocument.empty("<nested>");
        encodeIntoDocument(instance, doc, "");
        return doc.toNode();
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object bindProperty(
            PropertySchema prop,
            @Nullable Object pojoDefault,
            ConfigSection section,
            @Nullable ConfigDocument patchDoc,
            @Nullable Object hookContext
    ) {
        String primaryKey = prop.resolveKey(options.naming());
        String fullPath = section.fullPath(primaryKey);
        ConfigNode.MapEntry matched = section.node().entry(primaryKey);
        String matchedKey = primaryKey;

        if (matched == null && prop.alias() != null) {
            Alias alias = prop.alias();
            for (String aliasKey : alias.value()) {
                ConfigNode.MapEntry candidate = section.node().entry(aliasKey);
                if (candidate != null) {
                    matched = candidate;
                    matchedKey = aliasKey;
                    if (alias.warnOnUse()) {
                        section.diagnostics().warn(
                                DiagnosticCategory.SCHEMA,
                                candidate.keySpan(),
                                section.fullPath(aliasKey),
                                "Configuration key '" + aliasKey + "' is deprecated.",
                                "Use '" + primaryKey + "' instead."
                        );
                    }
                    break;
                }
            }
        }

        Class<?> rawType = prop.rawType();
        if (matched == null || (matched.value() instanceof ConfigNode.NullNode && rawType != Optional.class)) {
            Object fallback = resolveMissingValue(prop, matched != null ? null : pojoDefault, section, patchDoc, hookContext);
            if (matched == null && patchDoc != null) {
                patchMissingValue(prop, fullPath, fallback, patchDoc);
            }
            return fallback;
        }

        Object result;
        if (isNestedBindable(rawType)) {
            result = bind(rawType, section.section(matchedKey), patchDoc, hookContext);
        } else if (rawType == ConfigSection.class) {
            result = section.section(matchedKey);
        } else {
            ConfigValue val = new ConfigValue(section.source(), fullPath, matched.value(), section.diagnostics(), codecs);
            result = decodeWithRegistry(prop, val, pojoDefault);
        }

        if (Map.class.isAssignableFrom(rawType)) {
            result = applyTemplateIfPresent(prop, (Map<String, Object>) result, fullPath, patchDoc);
        }
        result = validate(prop, result, matched.value().span(), fullPath, section.diagnostics());
        if (patchDoc != null) {
            attachCommentsIfAbsent(prop, fullPath, patchDoc);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object resolveMissingValue(
            PropertySchema prop,
            @Nullable Object pojoDefault,
            ConfigSection section,
            @Nullable ConfigDocument patchDoc,
            @Nullable Object hookContext
    ) {
        String key = prop.resolveKey(options.naming());
        String fullPath = section.fullPath(key);

        if (prop.defaultValue() != null) {
            ConfigValue synthetic = new ConfigValue(
                    section.source(), fullPath, ConfigNode.ScalarNode.of(prop.defaultValue().value()),
                    section.diagnostics(), codecs
            );
            return validate(prop, decodeWithRegistry(prop, synthetic, pojoDefault),
                    SourceSpan.ofSource(section.source()), fullPath, section.diagnostics());
        }

        Class<?> rawType = prop.rawType();
        if (pojoDefault != null) {
            if (isNestedBindable(rawType)) {
                return bind(rawType, ConfigSection.empty(section.source(), fullPath, section.diagnostics(), codecs), patchDoc, hookContext);
            }
            if (Map.class.isAssignableFrom(rawType)) {
                return applyTemplateIfPresent(prop, new LinkedHashMap<>((Map<String, Object>) pojoDefault), fullPath, patchDoc);
            }
            return pojoDefault;
        }

        if (prop.version() != null && (rawType == int.class || rawType == Integer.class)) {
            return prop.version().value();
        }
        if (rawType == Optional.class) {
            return Optional.empty();
        }
        if (rawType == ConfigSection.class) {
            return ConfigSection.empty(section.source(), fullPath, section.diagnostics(), codecs);
        }
        if (List.class.isAssignableFrom(rawType)) {
            return List.of();
        }
        if (Set.class.isAssignableFrom(rawType)) {
            return Set.of();
        }
        if (Map.class.isAssignableFrom(rawType)) {
            return applyTemplateIfPresent(prop, new LinkedHashMap<>(), fullPath, patchDoc);
        }
        if (isNestedBindable(rawType)) {
            return bind(rawType, ConfigSection.empty(section.source(), fullPath, section.diagnostics(), codecs), patchDoc, hookContext);
        }
        if (rawType.isPrimitive()) {
            return primitiveZero(rawType);
        }
        if (prop.nonBlank() != null) {
            section.diagnostics().error(DiagnosticCategory.VALIDATION, section.span(), fullPath,
                    "Missing required non-blank property.");
        }
        return null;
    }

    private @Nullable Object decodeWithRegistry(PropertySchema prop, ConfigValue value, @Nullable Object fallback) {
        var codecOpt = codecs.find(prop.genericType());
        if (codecOpt.isEmpty()) {
            value.diagnostics().error(DiagnosticCategory.TYPE, value.span(), value.path(),
                    "No ConfigCodec registered for type " + prop.genericType().getTypeName() + ".");
            return fallback;
        }
        try {
            Object decoded = codecOpt.get().decode(value);
            return decoded != null ? decoded : fallback;
        } catch (ConfigDecodeException ex) {
            value.diagnostics().error(DiagnosticCategory.TYPE, value.span(), value.path(), ex.getMessage());
            return fallback != null ? fallback : (prop.rawType().isPrimitive() ? primitiveZero(prop.rawType()) : null);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Object validate(
            PropertySchema prop,
            @Nullable Object value,
            SourceSpan span,
            String path,
            ConfigDiagnostics diagnostics
    ) {
        Object current = value;
        if (prop.nonBlank() != null && (current == null || (current instanceof String s && s.isBlank()))) {
            diagnostics.error(DiagnosticCategory.VALIDATION, span, path, "Property must not be null or blank.");
        }
        if (prop.pattern() != null && current instanceof String s) {
            String regex = prop.pattern().value();
            if (!REGEX_CACHE.computeIfAbsent(regex, Pattern::compile).matcher(s).matches()) {
                String msg = prop.pattern().message().isEmpty()
                        ? "Value '" + s + "' does not match required pattern '" + regex + "'."
                        : prop.pattern().message();
                diagnostics.error(DiagnosticCategory.VALIDATION, span, path, msg);
            }
        }
        if (prop.range() != null && current instanceof Number num) {
            current = applyRange(prop.range(), num, prop.rawType(), span, path, diagnostics);
        }
        if (prop.check() != null) {
            try {
                Check.ConfigValidator validator = VALIDATOR_CACHE.computeIfAbsent(
                        prop.check().value(),
                        cls -> (Check.ConfigValidator<?>) instantiateNoArg(cls)
                );
                current = validator.validate(current);
            } catch (ConfigDecodeException ex) {
                diagnostics.error(DiagnosticCategory.VALIDATION, span, path, ex.getMessage());
            } catch (Exception ex) {
                diagnostics.error(DiagnosticCategory.VALIDATION, span, path, "Validator failed: " + ex.getMessage());
            }
        }
        return current;
    }

    private static Number applyRange(
            Range range,
            Number num,
            Class<?> targetType,
            SourceSpan span,
            String path,
            ConfigDiagnostics diagnostics
    ) {
        double d = num.doubleValue();
        double min = range.min();
        double max = range.max();
        if (d >= min && d <= max) {
            return num;
        }
        if (!range.clamp()) {
            diagnostics.error(DiagnosticCategory.VALIDATION, span, path,
                    "Value " + num + " out of range [" + min + ", " + max + "].");
            return num;
        }
        Number converted = castClampedNumber(Math.max(min, Math.min(max, d)), targetType);
        diagnostics.warn(DiagnosticCategory.VALIDATION, span, path,
                "Value " + num + " out of range [" + min + ", " + max + "], clamped to " + converted + ".");
        return converted;
    }

    private static Number castClampedNumber(double clamped, Class<?> targetType) {
        if (targetType == int.class || targetType == Integer.class) return (int) Math.round(clamped);
        if (targetType == long.class || targetType == Long.class) return Math.round(clamped);
        if (targetType == short.class || targetType == Short.class) return (short) Math.round(clamped);
        if (targetType == byte.class || targetType == Byte.class) return (byte) Math.round(clamped);
        if (targetType == float.class || targetType == Float.class) return (float) clamped;
        if (targetType == BigInteger.class) return BigInteger.valueOf(Math.round(clamped));
        if (targetType == BigDecimal.class) return BigDecimal.valueOf(clamped);
        return clamped;
    }

    private Map<String, Object> applyTemplateIfPresent(
            PropertySchema prop,
            @Nullable Map<String, Object> map,
            String fullPath,
            @Nullable ConfigDocument patchDoc
    ) {
        Map<String, Object> mutable = map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>();
        Type valType = TypeRef.typeArgument(prop.genericType(), 1);
        if (valType == null) {
            return Collections.unmodifiableMap(mutable);
        }
        Class<?> valClass = TypeRef.resolveRawType(valType);
        Template template = valClass.getAnnotation(Template.class);
        if (template == null) {
            return Collections.unmodifiableMap(mutable);
        }
        String defaultKey = template.name();
        if (template.value().shouldAdd(mutable.containsKey(defaultKey), mutable.isEmpty()) && isBindableObjectType(valClass)) {
            ConfigSection emptySub = ConfigSection.empty("<template>", fullPath + "." + defaultKey,
                    new ConfigDiagnostics(), codecs);
            Object defaultEntry = bind(valClass, emptySub, null, null);
            mutable.put(defaultKey, defaultEntry);
            if (patchDoc != null) {
                patchDoc.set(fullPath + "." + defaultKey, encodeToNode(defaultEntry));
            }
        }
        return Collections.unmodifiableMap(mutable);
    }

    private void patchMissingValue(PropertySchema prop, String fullPath, @Nullable Object value, ConfigDocument patchDoc) {
        if (value == null) {
            return;
        }
        if (!isNestedBindable(prop.rawType())) {
            patchDoc.set(fullPath, new CodecContext(codecs).encode(value, prop.genericType()));
        } else if (!patchDoc.has(fullPath)) {
            patchDoc.set(fullPath, ConfigNode.MapNode.EMPTY);
        }
        attachCommentsIfAbsent(prop, fullPath, patchDoc);
    }

    private static void applyHeaderIfAbsent(Class<?> type, ConfigDocument doc) {
        Header header = type.getAnnotation(Header.class);
        if (header != null && doc.headerComments().block().isEmpty()) {
            doc.setHeader(List.of(header.value()));
        }
    }

    private static void attachCommentsIfAbsent(PropertySchema prop, String fullPath, ConfigDocument doc) {
        if (prop.comment() != null) {
            doc.setBlockCommentsIfAbsent(fullPath, List.of(prop.comment().value()));
        }
        if (prop.inline() != null) {
            doc.setInlineCommentsIfAbsent(fullPath, List.of(prop.inline().value()));
        }
    }

    private void handleUnknownKeys(
            Class<?> ownerType,
            List<PropertySchema> props,
            ConfigSection section,
            @Nullable ConfigDocument patchDoc
    ) {
        if (options.unknownKeyPolicy() == UnknownKeyPolicy.IGNORE || section.isEmpty()) {
            return;
        }
        Set<String> allowedPrimary = new LinkedHashSet<>(props.size() + 1);
        Set<String> accepted = new LinkedHashSet<>(props.size() * 2 + 1);
        if (section.basePath().isEmpty()) {
            Version typeVersion = ownerType.getAnnotation(Version.class);
            if (typeVersion != null && !typeVersion.key().isEmpty()) {
                allowedPrimary.add(typeVersion.key());
                accepted.add(typeVersion.key());
            }
        }
        for (PropertySchema prop : props) {
            String primary = prop.resolveKey(options.naming());
            allowedPrimary.add(primary);
            accepted.add(primary);
            if (prop.alias() != null) {
                Collections.addAll(accepted, prop.alias().value());
            }
        }
        for (ConfigNode.MapEntry entry : section.node().entries().values()) {
            String key = entry.key();
            if (accepted.contains(key)) {
                continue;
            }
            String suggestion = SpellingSuggester.suggest(key, allowedPrimary);
            String hint = suggestion != null ? "Did you mean '" + suggestion + "'?" : null;
            SourceSpan span = entry.keySpan().isKnown() ? entry.keySpan() : entry.value().span();
            String path = section.fullPath(key);

            switch (options.unknownKeyPolicy()) {
                case ERROR -> section.diagnostics().error(DiagnosticCategory.SCHEMA, span, path,
                        "Unknown configuration key '" + key + "'.", hint);
                case WARN -> section.diagnostics().warn(DiagnosticCategory.SCHEMA, span, path,
                        "Unknown configuration key '" + key + "'.", hint);
                case PRUNE -> {
                    section.diagnostics().warn(DiagnosticCategory.SCHEMA, span, path,
                            "Removed unknown configuration key '" + key + "'.", hint);
                    if (patchDoc != null) {
                        patchDoc.remove(path);
                    }
                }
                case IGNORE -> {
                }
            }
        }
    }

    private boolean isNestedBindable(Class<?> rawType) {
        return !baseCodecs.hasExplicit(rawType) && isBindableObjectType(rawType);
    }

    private static boolean isBindableObjectType(Class<?> raw) {
        if (raw == null || raw == Object.class || raw.isPrimitive() || raw.isEnum() || raw.isArray()
                || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)
                || raw == Optional.class || raw == ConfigSection.class || raw == ConfigNode.class) {
            return false;
        }
        if (raw.getPackageName().startsWith("java.") || raw.getPackageName().startsWith("javax.")) {
            return false;
        }
        if (raw.isRecord()) {
            return true;
        }
        if (raw.isInterface()) {
            return false;
        }
        try {
            raw.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void runHooks(
            Object instance,
            Class<? extends Annotation> hookAnnotation,
            @Nullable Object context,
            ConfigDiagnostics diagnostics,
            SourceSpan span
    ) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(hookAnnotation)) {
                continue;
            }
            method.setAccessible(true);
            try {
                int params = method.getParameterCount();
                if (params == 0) {
                    method.invoke(instance);
                } else if (params == 1) {
                    Class<?> pType = method.getParameterTypes()[0];
                    if (pType == ConfigDiagnostics.class) {
                        method.invoke(instance, diagnostics);
                    } else if (context == null || pType.isInstance(context)) {
                        method.invoke(instance, context);
                    }
                }
            } catch (Exception e) {
                diagnostics.error(DiagnosticCategory.VALIDATION, span, instance.getClass().getSimpleName(),
                        "Hook @" + hookAnnotation.getSimpleName() + " '" + method.getName() + "' failed: " + e.getMessage());
            }
        }
    }

    private static <T> T instantiateNoArg(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot instantiate " + type.getName() + " with no-arg constructor", e);
        }
    }

    private static Object primitiveZero(Class<?> primitive) {
        if (primitive == boolean.class) return false;
        if (primitive == int.class) return 0;
        if (primitive == long.class) return 0L;
        if (primitive == double.class) return 0.0;
        if (primitive == float.class) return 0.0f;
        if (primitive == short.class) return (short) 0;
        if (primitive == byte.class) return (byte) 0;
        if (primitive == char.class) return '\0';
        return null;
    }
}
