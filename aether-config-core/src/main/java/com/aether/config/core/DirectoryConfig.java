package com.aether.config.core;

import com.aether.config.annotation.DefaultResources;
import com.aether.config.bind.ObjectBinder;
import com.aether.config.bind.SyncMode;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.ConfigException;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.migration.VersionManager;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.SourceSpan;
import com.aether.config.view.ConfigSection;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Directory-backed configuration loading all matching files into a merged {@code Map<String, V>}
 * with incremental file-level caching, reflection or custom {@link EntryFactory} decoding,
 * and duplicate key policies.
 */
public final class DirectoryConfig<V> {

    @FunctionalInterface
    public interface EntryFactory<V> {
        @Nullable V create(String id, ConfigSection section) throws Exception;
    }

    public enum DuplicateKeyPolicy {
        LAST_WRITE_WINS,
        KEEP_FIRST,
        ERROR_AND_SKIP
    }

    public enum DefaultSeedPolicy {
        ONLY_WHEN_EMPTY,
        FILL_MISSING,
        NEVER
    }

    private final ConfigManager manager;
    private final Path directory;
    private final @Nullable Class<V> valueType;
    private final @Nullable EntryFactory<V> entryFactory;
    private final boolean recursive;
    private final boolean fileAsEntry;
    private final boolean keyFromRelativePath;
    private final boolean preserveSubdirectories;
    private final @Nullable String resourcePrefix;
    private final @Nullable String rootKey;
    private final @Nullable ResourceProvider resourceProvider;
    private final @Nullable String[] defaultResourcePaths;
    private final DefaultSeedPolicy seedPolicy;
    private final DuplicateKeyPolicy duplicatePolicy;
    private final @Nullable Object hookContext;

    private final FileCache fileCache = new FileCache();
    private final Map<String, Map<String, V>> perFileEntries = new LinkedHashMap<>();
    private final Map<String, Path> perRelPathFile = new LinkedHashMap<>();
    private final AtomicReference<Map<String, Path>> entryFiles = new AtomicReference<>(Collections.emptyMap());
    private final AtomicReference<Map<String, V>> mergedEntries = new AtomicReference<>(Collections.emptyMap());
    private final AtomicReference<ConfigDiagnostics> lastDiagnostics = new AtomicReference<>(new ConfigDiagnostics());
    private final CopyOnWriteArrayList<BiConsumer<Map<String, V>, Map<String, V>>> reloadListeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private DirectoryConfig(Builder<V> builder) {
        this.manager = builder.manager;
        this.directory = builder.directory.toAbsolutePath().normalize();
        this.valueType = builder.valueType;
        this.entryFactory = builder.entryFactory;
        this.recursive = builder.recursive;
        this.fileAsEntry = builder.fileAsEntry;
        this.keyFromRelativePath = builder.keyFromRelativePath;
        this.preserveSubdirectories = builder.preserveSubdirectories;
        this.resourcePrefix = builder.resourcePrefix;
        this.rootKey = builder.rootKey;
        this.resourceProvider = builder.resourceProvider;
        this.defaultResourcePaths = builder.defaultResourcePaths;
        this.seedPolicy = builder.seedPolicy;
        this.duplicatePolicy = builder.duplicatePolicy;
        this.hookContext = builder.hookContext;
    }

    public Map<String, V> all() {
        return mergedEntries.get();
    }

    public @Nullable V get(@Nullable String key) {
        return key != null ? mergedEntries.get().get(key) : null;
    }

    public @Nullable Path sourceFile(@Nullable String key) {
        return key != null ? entryFiles.get().get(key) : null;
    }

    public DirectoryConfig<V> onReload(BiConsumer<Map<String, V>, Map<String, V>> listener) {
        reloadListeners.add(listener);
        return this;
    }

    public ConfigDiagnostics diagnostics() {
        return lastDiagnostics.get();
    }

    public boolean isFresh() {
        return fileCache.isDirectoryFresh(directory, recursive, manager.format());
    }

    public ReloadResult<Map<String, V>> reload() {
        ReloadResult<Map<String, V>> result;
        Map<String, V> previous;
        synchronized (lock) {
            previous = mergedEntries.get();
            if (!perFileEntries.isEmpty() && isFresh()) {
                return new ReloadResult<>(true, false, previous, new ConfigDiagnostics());
            }
            result = executeLoad(false);
        }
        if (result.changed()) {
            notifyReloadListeners(previous, result.value(), result.diagnostics());
        }
        return result;
    }

    public CompletableFuture<ReloadResult<Map<String, V>>> reloadAsync() {
        return reloadAsync(ForkJoinPool.commonPool());
    }

    public CompletableFuture<ReloadResult<Map<String, V>>> reloadAsync(Executor executor) {
        return CompletableFuture.supplyAsync(this::reload, executor);
    }

    public void saveEntry(String id, V value) throws IOException {
        Map<String, V> previous;
        Map<String, V> next;
        ConfigDiagnostics diag = new ConfigDiagnostics();
        synchronized (lock) {
            previous = mergedEntries.get();
            Path target = entryFiles.get().get(id);
            if (target == null) {
                if (!fileAsEntry) {
                    throw new IOException("Cannot infer target file for entry '" + id + "' when fileAsEntry is disabled");
                }
                String ext = manager.format().extensions().stream().findFirst().orElse(".yml");
                if (!ext.startsWith(".")) {
                    ext = "." + ext;
                }
                target = directory.resolve(id + ext).toAbsolutePath().normalize();
            }
            manager.saveObject(value, target);
            ReloadResult<Map<String, V>> reloaded = executeLoad(false);
            next = reloaded.value();
            diag = reloaded.diagnostics();
        }
        notifyReloadListeners(previous, next, diag);
    }

    private void notifyReloadListeners(Map<String, V> previous, Map<String, V> next, ConfigDiagnostics diag) {
        for (BiConsumer<Map<String, V>, Map<String, V>> listener : reloadListeners) {
            try {
                listener.accept(previous, next);
            } catch (Exception ex) {
                diag.warn(DiagnosticCategory.IO, SourceSpan.ofSource(directory.toString()), "",
                        "Directory reload listener threw an exception: " + ex.getMessage());
            }
        }
    }

    private ReloadResult<Map<String, V>> executeLoad(boolean initial) {
        ConfigDiagnostics diag = new ConfigDiagnostics();
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            diag.error(DiagnosticCategory.IO, SourceSpan.ofSource(directory.toString()), "",
                    "Failed to create directory: " + ex.getMessage());
            lastDiagnostics.set(diag);
            if (initial && manager.options().strictMode()) {
                throw new ConfigException("Failed to initialize directory " + directory, ex, diag);
            }
            return new ReloadResult<>(false, false, mergedEntries.get(), diag);
        }

        List<Path> files = FileCache.listMatchingFiles(directory, recursive, manager.format());
        if (seedPolicy != DefaultSeedPolicy.NEVER && resourceProvider != null) {
            if (seedPolicy == DefaultSeedPolicy.FILL_MISSING || files.isEmpty()) {
                seedDefaults(diag);
                files = FileCache.listMatchingFiles(directory, recursive, manager.format());
            }
        }

        boolean anyChanged = initial || files.size() != perFileEntries.size();
        Map<String, Map<String, V>> nextPerFile = new LinkedHashMap<>();
        Map<String, Path> nextRelToFile = new LinkedHashMap<>();
        ObjectBinder binder = manager.binder();

        for (Path file : files) {
            String rel = directory.relativize(file).toString().replace('\\', '/');
            nextRelToFile.put(rel, file);
            boolean fresh = !initial && fileCache.isFresh(file) && perFileEntries.containsKey(rel);
            if (fresh) {
                nextPerFile.put(rel, perFileEntries.get(rel));
                continue;
            }
            anyChanged = true;
            Map<String, V> fileEntries = parseSingleFile(file, rel, binder, diag);
            nextPerFile.put(rel, fileEntries);
        }

        fileCache.purgeStale(directory, recursive, manager.format());

        if (!anyChanged && !initial) {
            lastDiagnostics.set(diag);
            return new ReloadResult<>(true, false, mergedEntries.get(), diag);
        }

        Map<String, V> merged = new LinkedHashMap<>();
        Map<String, Path> mergedFiles = new LinkedHashMap<>();
        Map<String, String> keyOrigin = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, V>> fileEntry : nextPerFile.entrySet()) {
            String sourceRel = fileEntry.getKey();
            Path sourcePath = nextRelToFile.get(sourceRel);
            for (Map.Entry<String, V> item : fileEntry.getValue().entrySet()) {
                String id = item.getKey();
                if (merged.containsKey(id)) {
                    switch (duplicatePolicy) {
                        case ERROR_AND_SKIP -> {
                            diag.error(DiagnosticCategory.SCHEMA, SourceSpan.ofSource(sourceRel),
                                    rootKey != null ? rootKey + "." + id : id,
                                    "Duplicate entry id '" + id + "' (already defined in " + keyOrigin.get(id) + "); skipping.");
                            continue;
                        }
                        case KEEP_FIRST -> {
                            continue;
                        }
                        case LAST_WRITE_WINS -> {
                            // overwrite below
                        }
                    }
                }
                merged.put(id, item.getValue());
                if (sourcePath != null) {
                    mergedFiles.put(id, sourcePath);
                }
                keyOrigin.put(id, sourceRel);
            }
        }

        if (diag.hasErrors() && manager.options().strictMode()) {
            lastDiagnostics.set(diag);
            if (initial) {
                throw new ConfigException("Directory configuration failed in " + directory, diag);
            }
            return new ReloadResult<>(false, false, mergedEntries.get(), diag);
        }

        perFileEntries.clear();
        perFileEntries.putAll(nextPerFile);
        perRelPathFile.clear();
        perRelPathFile.putAll(nextRelToFile);
        entryFiles.set(Collections.unmodifiableMap(mergedFiles));
        Map<String, V> immutableMerged = Collections.unmodifiableMap(merged);
        mergedEntries.set(immutableMerged);
        lastDiagnostics.set(diag);
        return new ReloadResult<>(!diag.hasErrors(), true, immutableMerged, diag);
    }

    @SuppressWarnings("unchecked")
    private Map<String, V> parseSingleFile(
            Path file,
            String relPath,
            ObjectBinder binder,
            ConfigDiagnostics diag
    ) {
        String content;
        try {
            content = fileCache.read(file);
        } catch (IOException ex) {
            diag.error(DiagnosticCategory.IO, SourceSpan.ofSource(relPath), "",
                    "Failed to read file: " + ex.getMessage());
            return Map.of();
        }

        ConfigNode.MapNode parsed = manager.format().parse(relPath, new StringReader(content), diag);

        if (fileAsEntry) {
            ConfigDocument doc = ConfigDocument.fromNode(parsed);
            boolean migrated = false;
            if (valueType != null) {
                migrated = VersionManager.migrateIfNeeded(
                        valueType, file, false, doc, manager.options().naming(), manager.migrationFor(valueType), diag
                );
            }
            ConfigNode.MapNode interpolated = manager.interpolator().interpolate(doc.toNode(), diag);
            ConfigSection fileRoot = ConfigSection.ofNode(relPath, "", interpolated, diag, binder.codecs());
            ConfigSection scope = fileRoot;
            if (rootKey != null && !rootKey.isEmpty()) {
                if (!fileRoot.has(rootKey)) {
                    return Map.of();
                }
                scope = fileRoot.section(rootKey);
            }
            String entryId = deriveFileId(relPath);
            boolean allowPatch = manager.options().syncMode() == SyncMode.PATCH_MISSING
                    && (rootKey == null || rootKey.isEmpty());
            ConfigDocument patchTarget = allowPatch ? doc : null;
            try {
                V value;
                if (entryFactory != null) {
                    value = entryFactory.create(entryId, scope);
                } else if (valueType != null) {
                    value = binder.bind(valueType, scope, patchTarget, hookContext);
                } else {
                    value = null;
                }
                if (patchTarget != null && valueType != null && (migrated || patchTarget.isModified()) && !diag.hasErrors()) {
                    manager.ensureVersionKey(patchTarget, valueType);
                    manager.writeDocumentAtomically(patchTarget.toNode(), file);
                }
                if (value == null) {
                    return Map.of();
                }
                return Map.of(entryId, value);
            } catch (Exception ex) {
                diag.error(DiagnosticCategory.TYPE, scope.span(), entryId,
                        "Failed to load entry '" + entryId + "': " + ex.getMessage());
                return Map.of();
            }
        }

        ConfigNode.MapNode interpolated = manager.interpolator().interpolate(parsed, diag);
        ConfigSection fileRoot = ConfigSection.ofNode(relPath, "", interpolated, diag, binder.codecs());

        ConfigSection scope = fileRoot;
        if (rootKey != null && !rootKey.isEmpty()) {
            if (!fileRoot.has(rootKey)) {
                return Map.of();
            }
            if (manager.options().unknownKeyPolicy() != com.aether.config.bind.UnknownKeyPolicy.IGNORE) {
                fileRoot.warnUnknownKeys(java.util.Set.of(rootKey));
            }
            scope = fileRoot.section(rootKey);
        }

        Map<String, V> entries = new LinkedHashMap<>();
        for (String key : scope.keys()) {
            ConfigSection entrySection = scope.section(key);
            try {
                V value;
                if (entryFactory != null) {
                    value = entryFactory.create(key, entrySection);
                } else if (valueType != null) {
                    value = binder.bind(valueType, entrySection, null, hookContext);
                } else {
                    value = null;
                }
                if (value != null) {
                    entries.put(key, value);
                }
            } catch (Exception ex) {
                diag.error(DiagnosticCategory.TYPE, entrySection.span(), scope.fullPath(key),
                        "Failed to load entry '" + key + "': " + ex.getMessage());
            }
        }
        return entries;
    }

    private String deriveFileId(String relPath) {
        String base = relPath;
        if (!keyFromRelativePath) {
            int slash = base.lastIndexOf('/');
            if (slash >= 0) {
                base = base.substring(slash + 1);
            }
        }
        int dot = base.lastIndexOf('.');
        int lastSlash = base.lastIndexOf('/');
        return dot > lastSlash ? base.substring(0, dot) : base;
    }

    private void seedDefaults(ConfigDiagnostics diag) {
        String[] paths = defaultResourcePaths;
        if (paths == null && valueType != null) {
            DefaultResources ann = valueType.getAnnotation(DefaultResources.class);
            if (ann != null) {
                paths = ann.value();
            }
        }
        if (paths == null || resourceProvider == null) {
            return;
        }
        for (String resPath : paths) {
            String normalized = resPath.replace('\\', '/');
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            String relTarget;
            if (resourcePrefix != null && !resourcePrefix.isEmpty()) {
                String cleanPrefix = resourcePrefix.replace('\\', '/');
                if (cleanPrefix.startsWith("/")) {
                    cleanPrefix = cleanPrefix.substring(1);
                }
                if (!cleanPrefix.endsWith("/")) {
                    cleanPrefix = cleanPrefix + "/";
                }
                relTarget = normalized.startsWith(cleanPrefix)
                        ? normalized.substring(cleanPrefix.length())
                        : (normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized);
            } else if (preserveSubdirectories) {
                relTarget = normalized;
            } else {
                relTarget = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            }
            Path dest = directory.resolve(relTarget);
            if (Files.exists(dest)) {
                continue;
            }
            try (InputStream in = resourceProvider.open(resPath)) {
                if (in == null) {
                    diag.warn(DiagnosticCategory.IO, SourceSpan.ofSource(resPath), "",
                            "Default resource not found: " + resPath);
                    continue;
                }
                if (dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }
                Files.copy(in, dest);
            } catch (IOException ex) {
                diag.error(DiagnosticCategory.IO, SourceSpan.ofSource(relTarget), "",
                        "Failed to copy default resource '" + resPath + "': " + ex.getMessage());
            }
        }
    }

    public static final class Builder<V> {

        private final ConfigManager manager;
        private final Path directory;
        private @Nullable Class<V> valueType;
        private @Nullable EntryFactory<V> entryFactory;
        private boolean recursive = false;
        private boolean fileAsEntry = false;
        private boolean keyFromRelativePath = false;
        private boolean preserveSubdirectories = false;
        private @Nullable String resourcePrefix;
        private @Nullable String rootKey;
        private @Nullable ResourceProvider resourceProvider;
        private @Nullable String[] defaultResourcePaths;
        private DefaultSeedPolicy seedPolicy = DefaultSeedPolicy.ONLY_WHEN_EMPTY;
        private DuplicateKeyPolicy duplicatePolicy = DuplicateKeyPolicy.LAST_WRITE_WINS;
        private @Nullable Object hookContext;

        Builder(ConfigManager manager, Path directory) {
            this.manager = manager;
            this.directory = directory;
        }

        @SuppressWarnings("unchecked")
        public <T> Builder<T> bind(Class<T> type) {
            Builder<T> typed = (Builder<T>) this;
            typed.valueType = type;
            typed.entryFactory = null;
            return typed;
        }

        @SuppressWarnings("unchecked")
        public <T> Builder<T> factory(EntryFactory<T> factory) {
            Builder<T> typed = (Builder<T>) this;
            typed.entryFactory = factory;
            typed.valueType = null;
            return typed;
        }

        public Builder<V> recursive() {
            this.recursive = true;
            return this;
        }

        public Builder<V> fileAsEntry() {
            this.fileAsEntry = true;
            return this;
        }

        public Builder<V> fileAsEntry(boolean keyFromRelativePath) {
            this.fileAsEntry = true;
            this.keyFromRelativePath = keyFromRelativePath;
            return this;
        }

        public Builder<V> preserveSubdirectories(boolean preserveSubdirectories) {
            this.preserveSubdirectories = preserveSubdirectories;
            return this;
        }

        public Builder<V> resourcePrefix(@Nullable String resourcePrefix) {
            this.resourcePrefix = resourcePrefix;
            return this;
        }

        public Builder<V> rootKey(@Nullable String rootKey) {
            this.rootKey = rootKey;
            return this;
        }

        public Builder<V> defaults(ResourceProvider provider, String... resourcePaths) {
            return defaults(provider, this.seedPolicy, resourcePaths);
        }

        public Builder<V> defaults(ResourceProvider provider, DefaultSeedPolicy seedPolicy, String... resourcePaths) {
            this.resourceProvider = provider;
            this.seedPolicy = seedPolicy;
            this.defaultResourcePaths = resourcePaths.length > 0 ? resourcePaths : null;
            return this;
        }

        public Builder<V> duplicatePolicy(DuplicateKeyPolicy duplicatePolicy) {
            this.duplicatePolicy = duplicatePolicy;
            return this;
        }

        public Builder<V> context(@Nullable Object hookContext) {
            this.hookContext = hookContext;
            return this;
        }

        public DirectoryConfig<V> load() {
            if (valueType == null && entryFactory == null) {
                throw new IllegalStateException("Either bind(Class) or factory(EntryFactory) must be configured");
            }
            DirectoryConfig<V> cfg = new DirectoryConfig<>(this);
            cfg.executeLoad(true);
            return cfg;
        }
    }
}
