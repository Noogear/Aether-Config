package com.aether.config.bind;

import com.aether.config.annotation.Check;
import com.aether.config.annotation.Header;
import com.aether.config.annotation.Polymorphic;
import com.aether.config.annotation.PostLoad;
import com.aether.config.annotation.PreLoad;
import com.aether.config.annotation.Range;
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

    private static final ClassValue<Check.ConfigValidator<?>> VALIDATOR_CACHE = new ClassValue<>() {
        @Override
        protected Check.ConfigValidator<?> computeValue(Class<?> type) {
            return (Check.ConfigValidator<?>) instantiateNoArg(type);
        }
    };
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
        return bindInternal(type, section, patchDoc, hookContext, Set.of());
    }

    @SuppressWarnings("unchecked")
    private <T> T bindInternal(
            Class<T> type,
            ConfigSection section,
            @Nullable ConfigDocument patchDoc,
            @Nullable Object hookContext,
            Set<String> extraAllowedKeys
    ) {
        if (isPolymorphicBase(type)) {
            return bindPolymorphic(type, section, patchDoc, hookContext);
        }
        List<PropertySchema> props = PropertySchema.forType(type);
        handleUnknownKeys(type, props, section, patchDoc, extraAllowedKeys);
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

    @SuppressWarnings("unchecked")
    private <T> @Nullable T bindPolymorphic(
            Class<T> baseType,
            ConfigSection section,
            @Nullable ConfigDocument patchDoc,
            @Nullable Object hookContext
    ) {
        String typeKey = resolvePolymorphicTypeKey(baseType);
        ConfigNode.MapEntry typeEntry = section.node().entry(typeKey);
        if (typeEntry == null || typeEntry.value() instanceof ConfigNode.NullNode) {
            section.diagnostics().error(
                    DiagnosticCategory.SCHEMA,
                    section.span(),
                    section.exactPath(typeKey),
                    "Missing polymorphic type discriminator '" + typeKey + "' for " + baseType.getSimpleName() + "."
            );
            return null;
        }
        Object rawName = typeEntry.value().rawValue();
        String typeName = rawName != null ? String.valueOf(rawName).trim() : "";
        Class<?> subType = resolvePolymorphicSubType(baseType, typeName);
        if (subType == null || !baseType.isAssignableFrom(subType)) {
            section.diagnostics().error(
                    DiagnosticCategory.TYPE,
                    typeEntry.value().span(),
                    section.exactPath(typeKey),
                    "Unknown polymorphic subtype '" + typeName + "' for " + baseType.getSimpleName() + "."
            );
            return null;
        }
        return (T) bindInternal(subType, section, patchDoc, hookContext, Set.of(typeKey));
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
            String path = ConfigDocument.joinPath(prefix, key);
            if (!doc.has(path)) {
                continue;
            }
            attachCommentsIfAbsent(prop, path, doc);
            if (isNestedBindable(prop.rawType()) && !isPolymorphicBase(prop.rawType())) {
                syncMetaComments(prop.rawType(), doc, path);
            }
        }
    }

    private void encodeIntoDocument(Object instance, ConfigDocument doc, String prefix) {
        if (prefix.isEmpty()) {
            applyHeaderIfAbsent(instance.getClass(), doc);
        }
        Class<?> polyBase = findPolymorphicBase(instance.getClass());
        if (polyBase != null) {
            String typeKey = resolvePolymorphicTypeKey(polyBase);
            String typeName = resolvePolymorphicTypeName(polyBase, instance.getClass());
            doc.set(ConfigDocument.joinPath(prefix, typeKey), ConfigNode.ScalarNode.of(typeName));
        }
        CodecContext ctx = new CodecContext(codecs);
        for (PropertySchema prop : PropertySchema.forType(instance.getClass())) {
            String key = prop.resolveKey(options.naming());
            String path = ConfigDocument.joinPath(prefix, key);
            Object val = prop.get(instance);
            if (val == null) {
                continue;
            }
            if (isNestedBindable(prop.rawType()) || isPolymorphicBase(prop.rawType())) {
                encodeIntoDocument(val, doc, path);
                if (!doc.has(path)) {
                    doc.set(path, ConfigNode.MapNode.EMPTY);
                }
            } else {
                doc.set(path, ctx.encode(val, prop.genericType()));
            }
            if (prop.comment() != null) {
                doc.setBlockCommentsIfAbsent(path, List.of(prop.comment().value()));
            }
            if (prop.inline() != null) {
                doc.setInlineCommentsIfAbsent(path, List.of(prop.inline().value()));
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
        String fullPath = section.exactPath(primaryKey);
        ConfigNode.MapEntry matched = section.node().entry(primaryKey);

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
            result = bind(rawType, section.exactSection(primaryKey), patchDoc, hookContext);
        } else if (rawType == ConfigSection.class) {
            result = section.exactSection(primaryKey);
        } else {
            ConfigValue val = new ConfigValue(section.source(), fullPath, matched.value(), section.diagnostics(), codecs);
            result = decodeWithRegistry(prop, val, pojoDefault);
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
        String fullPath = section.exactPath(key);

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
                runHooks(pojoDefault, PostLoad.class, hookContext, section.diagnostics(), section.span());
                return pojoDefault;
            }
            if (List.class.isAssignableFrom(rawType)) {
                return List.copyOf((Collection<?>) pojoDefault);
            }
            if (Set.class.isAssignableFrom(rawType)) {
                return Collections.unmodifiableSet(new LinkedHashSet<>((Collection<?>) pojoDefault));
            }
            if (Map.class.isAssignableFrom(rawType)) {
                return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) pojoDefault));
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
            return Map.of();
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
                Check.ConfigValidator validator = VALIDATOR_CACHE.get(prop.check().value());
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

    private void patchMissingValue(PropertySchema prop, String fullPath, @Nullable Object value, ConfigDocument patchDoc) {
        if (value == null) {
            return;
        }
        if (isPolymorphicBase(prop.rawType())) {
            encodeIntoDocument(value, patchDoc, fullPath);
        } else if (!isNestedBindable(prop.rawType())) {
            patchDoc.set(fullPath, new CodecContext(codecs).encode(value, prop.genericType()));
        } else {
            encodeIntoDocument(value, patchDoc, fullPath);
            if (!patchDoc.has(fullPath)) {
                patchDoc.set(fullPath, ConfigNode.MapNode.EMPTY);
            }
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
            @Nullable ConfigDocument patchDoc,
            Set<String> extraAllowedKeys
    ) {
        if (options.unknownKeyPolicy() == UnknownKeyPolicy.IGNORE || section.isEmpty()) {
            return;
        }
        Set<String> allowed = new LinkedHashSet<>(props.size() + extraAllowedKeys.size() + 1);
        allowed.addAll(extraAllowedKeys);
        if (section.basePath().isEmpty()) {
            Version typeVersion = ownerType.getAnnotation(Version.class);
            if (typeVersion != null && !typeVersion.key().isEmpty()) {
                allowed.add(typeVersion.key());
            }
        }
        for (PropertySchema prop : props) {
            allowed.add(prop.resolveKey(options.naming()));
        }
        for (ConfigNode.MapEntry entry : section.node().entries().values()) {
            String key = entry.key();
            if (allowed.contains(key)) {
                continue;
            }
            String suggestion = SpellingSuggester.suggest(key, allowed);
            String hint = suggestion != null ? "Did you mean '" + suggestion + "'?" : null;
            SourceSpan span = entry.keySpan().isKnown() ? entry.keySpan() : entry.value().span();
            String path = section.exactPath(key);

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

    private static boolean isPolymorphicBase(Class<?> raw) {
        return raw != null && (raw.isAnnotationPresent(Polymorphic.class) || raw.isSealed());
    }

    private static @Nullable Class<?> findPolymorphicBase(Class<?> concreteType) {
        if (concreteType == null) {
            return null;
        }
        if (isPolymorphicBase(concreteType)) {
            return concreteType;
        }
        for (Class<?> sup = concreteType.getSuperclass(); sup != null && sup != Object.class; sup = sup.getSuperclass()) {
            if (isPolymorphicBase(sup)) {
                return sup;
            }
        }
        for (Class<?> iface : concreteType.getInterfaces()) {
            if (isPolymorphicBase(iface)) {
                return iface;
            }
        }
        return null;
    }

    private static String resolvePolymorphicTypeKey(Class<?> baseType) {
        Polymorphic poly = baseType.getAnnotation(Polymorphic.class);
        return poly != null && !poly.typeKey().isEmpty() ? poly.typeKey() : "type";
    }

    private static @Nullable Class<?> resolvePolymorphicSubType(Class<?> baseType, String rawName) {
        if (rawName.isEmpty()) {
            return null;
        }
        String normalized = rawName.trim().replace('_', '-');
        Polymorphic poly = baseType.getAnnotation(Polymorphic.class);
        if (poly != null) {
            for (Polymorphic.SubType sub : poly.value()) {
                if (sub.name().equalsIgnoreCase(rawName.trim())
                        || sub.name().replace('_', '-').equalsIgnoreCase(normalized)) {
                    return sub.type();
                }
            }
        }
        Class<?>[] permitted = baseType.getPermittedSubclasses();
        if (permitted != null) {
            for (Class<?> sub : permitted) {
                String simple = sub.getSimpleName();
                String kebab = NamingStrategy.KEBAB_CASE.format(simple);
                if (simple.equalsIgnoreCase(rawName.trim()) || kebab.equalsIgnoreCase(normalized)) {
                    return sub;
                }
            }
        }
        return null;
    }

    private static String resolvePolymorphicTypeName(Class<?> baseType, Class<?> concreteType) {
        Polymorphic poly = baseType.getAnnotation(Polymorphic.class);
        if (poly != null) {
            for (Polymorphic.SubType sub : poly.value()) {
                if (sub.type() == concreteType) {
                    return sub.name();
                }
            }
        }
        return NamingStrategy.KEBAB_CASE.format(concreteType.getSimpleName());
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
        if (isPolymorphicBase(raw)) {
            return true;
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
