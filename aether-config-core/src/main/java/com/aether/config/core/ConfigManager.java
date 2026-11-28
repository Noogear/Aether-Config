package com.aether.config.core;

import com.aether.config.bind.BindOptions;
import com.aether.config.bind.NamingStrategy;
import com.aether.config.bind.ObjectBinder;
import com.aether.config.bind.SyncMode;
import com.aether.config.bind.UnknownKeyPolicy;
import com.aether.config.codec.CodecRegistry;
import com.aether.config.codec.ConfigCodec;
import com.aether.config.codec.TypeRef;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.ConfigException;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.format.ConfigFormat;
import com.aether.config.interpolate.InterpolationMode;
import com.aether.config.interpolate.NodeInterpolator;
import com.aether.config.migration.MigrationChain;
import com.aether.config.migration.VersionManager;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.SourceSpan;
import com.aether.config.view.ConfigSection;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Instance-scoped configuration engine coordinating format parsing, interpolation,
 * version migration, declarative binding, imperative views, and atomic reloads.
 */
public final class ConfigManager {

    private final ConfigFormat format;
    private final BindOptions options;
    private final ObjectBinder binder;
    private final NodeInterpolator interpolator;
    private final Function<String, @Nullable String> envLookup;
    private final Function<String, @Nullable String> sysLookup;
    private final FileCache fileCache = new FileCache();
    private final Map<Class<?>, MigrationChain> migrations;

    private ConfigManager(Builder builder) {
        this.format = Objects.requireNonNull(builder.format, "ConfigFormat must be provided (e.g., YamlFormat.create())");
        this.options = new BindOptions(
                builder.naming,
                builder.unknownKeyPolicy,
                builder.syncMode,
                builder.strictMode,
                builder.interpolation
        );
        this.binder = new ObjectBinder(builder.codecs, this.options);
        this.envLookup = builder.envLookup;
        this.sysLookup = builder.sysLookup;
        this.interpolator = new NodeInterpolator(builder.interpolation, builder.envLookup, builder.sysLookup);
        this.migrations = Map.copyOf(builder.migrations);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a mutable {@link Builder} inheriting this manager's format, codecs, options, lookups, and migrations.
     * Useful for addons to register private codecs/migrations without mutating the parent manager.
     */
    public Builder fork() {
        return new Builder()
                .format(this.format)
                .codecsCopy(this.binder.codecs().copy())
                .naming(this.options.naming())
                .unknownKeys(this.options.unknownKeyPolicy())
                .syncMode(this.options.syncMode())
                .strictMode(this.options.strictMode())
                .interpolation(this.options.interpolation())
                .envLookup(this.envLookup)
                .sysLookup(this.sysLookup)
                .migrationsCopy(this.migrations);
    }

    public Builder toBuilder() {
        return fork();
    }

    public ConfigFormat format() {
        return format;
    }

    public BindOptions options() {
        return options;
    }

    public ObjectBinder binder() {
        return binder;
    }

    public CodecRegistry codecs() {
        return binder.codecs();
    }

    public NodeInterpolator interpolator() {
        return interpolator;
    }

    public FileCache fileCache() {
        return fileCache;
    }

    public <T> ConfigHandle<T> load(Class<T> type, Path file) {
        return load(type, file, null, null, null);
    }

    public <T> ConfigHandle<T> load(
            Class<T> type,
            Path file,
            @Nullable ResourceProvider resourceProvider,
            @Nullable String defaultResourcePath
    ) {
        return load(type, file, resourceProvider, defaultResourcePath, null);
    }

    public <T> ConfigHandle<T> load(
            Class<T> type,
            Path file,
            @Nullable ResourceProvider resourceProvider,
            @Nullable String defaultResourcePath,
            @Nullable Object hookContext
    ) {
        LoadOutcome<T> outcome = loadInternal(type, file, resourceProvider, defaultResourcePath, hookContext);
        if (!outcome.applied()) {
            throw new ConfigException("Failed to load configuration " + file, outcome.diagnostics());
        }
        return new ConfigHandle<>(
                this, type, file, resourceProvider, defaultResourcePath, hookContext,
                outcome.value(), outcome.diagnostics()
        );
    }

    public ConfigSection parseSection(String sourceName, Reader reader, ConfigDiagnostics diagnostics) {
        ConfigNode.MapNode parsed = format.parse(sourceName, reader, diagnostics);
        ConfigNode.MapNode interpolated = interpolator.interpolate(parsed, diagnostics);
        return ConfigSection.ofNode(sourceName, "", interpolated, diagnostics, binder.codecs());
    }

    public ConfigSection loadSection(
            Path file,
            @Nullable ResourceProvider resourceProvider,
            @Nullable String defaultResourcePath,
            ConfigDiagnostics diagnostics
    ) {
        String sourceName = file.getFileName() != null ? file.getFileName().toString() : file.toString();
        if (Files.notExists(file) && resourceProvider != null && defaultResourcePath != null) {
            copyDefaultResource(file, resourceProvider, defaultResourcePath, sourceName, diagnostics);
        }
        if (Files.notExists(file)) {
            return ConfigSection.empty(sourceName, diagnostics, binder.codecs());
        }
        try {
            String content = fileCache.read(file);
            return parseSection(sourceName, new StringReader(content), diagnostics);
        } catch (IOException ex) {
            diagnostics.error(DiagnosticCategory.IO, SourceSpan.ofSource(sourceName), "",
                    "Failed to read file: " + ex.getMessage());
            return ConfigSection.empty(sourceName, diagnostics, binder.codecs());
        }
    }

    public DirectoryConfig.Builder<Object> directory(Path directory) {
        return new DirectoryConfig.Builder<>(this, directory);
    }

    public <T> void saveObject(T instance, Path file) throws IOException {
        ConfigDocument doc;
        if (Files.isRegularFile(file)) {
            ConfigDiagnostics probeDiag = new ConfigDiagnostics();
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            ConfigNode.MapNode existingNode = format.parse(file.getFileName().toString(), new StringReader(existing), probeDiag);
            if (!probeDiag.hasErrors()) {
                doc = ConfigDocument.fromNode(existingNode);
                ConfigNode.MapNode encoded = binder.encodeDocumentNode(instance);
                Set<String> extraRootKeep = new LinkedHashSet<>();
                VersionManager.VersionDescriptor ver = VersionManager.inspect(instance.getClass(), options.naming());
                if (ver != null && ver.expectedVersion() >= 0) {
                    extraRootKeep.add(ver.key());
                }
                mergeGeneratedIntoDocument(encoded, doc, "", extraRootKeep);
                binder.syncCommentsAndDefaults(instance, doc);
            } else {
                doc = ConfigDocument.fromNode(binder.encodeDocumentNode(instance));
            }
        } else {
            doc = ConfigDocument.fromNode(binder.encodeDocumentNode(instance));
        }

        ensureVersionKey(doc, instance.getClass());
        writeDocumentAtomically(doc.toNode(), file);
    }

    <T> LoadOutcome<T> loadInternal(
            Class<T> type,
            Path file,
            @Nullable ResourceProvider resourceProvider,
            @Nullable String defaultResourcePath,
            @Nullable Object hookContext
    ) {
        ConfigDiagnostics diagnostics = new ConfigDiagnostics();
        String sourceName = file.getFileName() != null ? file.getFileName().toString() : file.toString();
        boolean existedBefore = Files.exists(file);

        if (!existedBefore && resourceProvider != null && defaultResourcePath != null) {
            if (copyDefaultResource(file, resourceProvider, defaultResourcePath, sourceName, diagnostics)) {
                existedBefore = true;
            }
        }

        boolean isNewFile = !existedBefore;
        ConfigNode.MapNode rawNode = ConfigNode.MapNode.EMPTY;

        if (existedBefore) {
            try {
                String content = fileCache.read(file);
                rawNode = format.parse(sourceName, new StringReader(content), diagnostics);
                if (diagnostics.hasErrors()) {
                    return new LoadOutcome<>(false, null, diagnostics);
                }
            } catch (IOException ex) {
                diagnostics.error(DiagnosticCategory.IO, SourceSpan.ofSource(sourceName), "",
                        "Failed to read file: " + ex.getMessage());
                return new LoadOutcome<>(false, null, diagnostics);
            }
        }

        ConfigDocument doc = ConfigDocument.fromNode(rawNode);
        boolean migrated = VersionManager.migrateIfNeeded(
                type, file, isNewFile, doc, options.naming(), migrations.get(type), diagnostics
        );
        if (diagnostics.hasErrors() && options.strictMode()) {
            return new LoadOutcome<>(false, null, diagnostics);
        }

        ConfigNode.MapNode interpolated = interpolator.interpolate(doc.toNode(), diagnostics);
        ConfigSection section = ConfigSection.ofNode(sourceName, "", interpolated, diagnostics, binder.codecs());

        boolean allowPatch = options.syncMode() == SyncMode.PATCH_MISSING
                || (options.syncMode() == SyncMode.CREATE_IF_MISSING && isNewFile);
        ConfigDocument patchTarget = allowPatch ? doc : null;

        T bound = binder.bind(type, section, patchTarget, hookContext);

        if (diagnostics.hasErrors() && options.strictMode()) {
            return new LoadOutcome<>(false, null, diagnostics);
        }

        if (patchTarget != null && (isNewFile || migrated || patchTarget.isModified())) {
            ensureVersionKey(patchTarget, type);
            if (!diagnostics.hasErrors()) {
                try {
                    writeDocumentAtomically(patchTarget.toNode(), file);
                } catch (IOException ex) {
                    diagnostics.error(DiagnosticCategory.IO, SourceSpan.ofSource(sourceName), "",
                            "Failed to write synchronized configuration: " + ex.getMessage());
                }
            }
        }

        return new LoadOutcome<>(true, bound, diagnostics);
    }

    void ensureVersionKey(ConfigDocument doc, Class<?> type) {
        VersionManager.VersionDescriptor ver = VersionManager.inspect(type, options.naming());
        if (ver != null && ver.expectedVersion() >= 0 && !doc.has(ver.key())) {
            doc.setRaw(ver.key(), ver.expectedVersion());
        }
    }

    void writeDocumentAtomically(ConfigNode.MapNode root, Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringWriter sw = new StringWriter();
        format.write(root, sw);
        String rendered = sw.toString();

        Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."), ".cfg-", ".tmp");
        try {
            Files.writeString(tmp, rendered, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
            fileCache.put(abs, rendered);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Nullable MigrationChain migrationFor(Class<?> type) {
        return migrations.get(type);
    }

    private static void mergeGeneratedIntoDocument(
            ConfigNode.MapNode encoded,
            ConfigDocument doc,
            String prefix,
            Set<String> extraRootKeep
    ) {
        if (prefix.isEmpty() && !encoded.comments().block().isEmpty() && doc.headerComments().block().isEmpty()) {
            doc.setHeader(encoded.comments().block());
        }
        Set<String> keepKeys = new LinkedHashSet<>(encoded.entries().keySet());
        if (prefix.isEmpty() && !extraRootKeep.isEmpty()) {
            keepKeys.addAll(extraRootKeep);
        }
        doc.pruneMissingChildren(prefix, keepKeys);
        for (ConfigNode.MapEntry entry : encoded.entries().values()) {
            String path = ConfigDocument.joinPath(prefix, entry.key());
            if (entry.value() instanceof ConfigNode.MapNode nested && !nested.isEmpty()) {
                mergeGeneratedIntoDocument(nested, doc, path, Set.of());
            } else {
                doc.set(path, entry.value());
            }
            if (!entry.keyComments().block().isEmpty()) {
                doc.setBlockCommentsIfAbsent(path, entry.keyComments().block());
            }
            if (!entry.keyComments().inline().isEmpty()) {
                doc.setInlineCommentsIfAbsent(path, entry.keyComments().inline());
            } else if (!entry.value().comments().inline().isEmpty()) {
                doc.setInlineCommentsIfAbsent(path, entry.value().comments().inline());
            }
        }
    }

    private static boolean copyDefaultResource(
            Path target,
            ResourceProvider provider,
            String resourcePath,
            String sourceName,
            ConfigDiagnostics diagnostics
    ) {
        try (InputStream in = provider.open(resourcePath)) {
            if (in == null) {
                return false;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(in, target);
            return true;
        } catch (IOException ex) {
            diagnostics.error(DiagnosticCategory.IO, SourceSpan.ofSource(sourceName), "",
                    "Failed to copy default resource '" + resourcePath + "': " + ex.getMessage());
            return false;
        }
    }

    record LoadOutcome<T>(boolean applied, @Nullable T value, ConfigDiagnostics diagnostics) {
    }

    public static final class Builder {
        private ConfigFormat format;
        private CodecRegistry codecs = CodecRegistry.defaults();
        private NamingStrategy naming = NamingStrategy.KEBAB_CASE;
        private UnknownKeyPolicy unknownKeyPolicy = UnknownKeyPolicy.WARN;
        private SyncMode syncMode = SyncMode.PATCH_MISSING;
        private boolean strictMode = false;
        private InterpolationMode interpolation = InterpolationMode.ENV_AND_SYS;
        private Function<String, @Nullable String> envLookup = System::getenv;
        private Function<String, @Nullable String> sysLookup = System::getProperty;
        private final Map<Class<?>, MigrationChain> migrations = new ConcurrentHashMap<>();

        private Builder() {
        }

        private Builder codecsCopy(CodecRegistry copy) {
            this.codecs = copy;
            return this;
        }

        private Builder migrationsCopy(Map<Class<?>, MigrationChain> existing) {
            this.migrations.putAll(existing);
            return this;
        }

        public Builder format(ConfigFormat format) {
            this.format = format;
            return this;
        }

        public Builder codecs(Consumer<CodecRegistry> customizer) {
            customizer.accept(this.codecs);
            return this;
        }

        public <T> Builder registerCodec(Class<T> type, ConfigCodec<T> codec) {
            this.codecs.register(type, codec);
            return this;
        }

        public <T> Builder registerCodec(TypeRef<T> typeRef, ConfigCodec<T> codec) {
            this.codecs.register(typeRef, codec);
            return this;
        }

        public Builder naming(NamingStrategy naming) {
            this.naming = naming;
            return this;
        }

        public Builder unknownKeys(UnknownKeyPolicy policy) {
            this.unknownKeyPolicy = policy;
            return this;
        }

        public Builder syncMode(SyncMode syncMode) {
            this.syncMode = syncMode;
            return this;
        }

        public Builder strictMode(boolean strictMode) {
            this.strictMode = strictMode;
            return this;
        }

        public Builder interpolation(InterpolationMode mode) {
            this.interpolation = mode;
            return this;
        }

        public Builder envLookup(Function<String, @Nullable String> envLookup) {
            this.envLookup = envLookup;
            return this;
        }

        public Builder sysLookup(Function<String, @Nullable String> sysLookup) {
            this.sysLookup = sysLookup;
            return this;
        }

        public Builder migrations(Class<?> configType, MigrationChain chain) {
            this.migrations.put(configType, chain);
            return this;
        }

        public ConfigManager build() {
            return new ConfigManager(this);
        }
    }
}
