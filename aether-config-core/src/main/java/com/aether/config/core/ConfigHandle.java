package com.aether.config.core;

import com.aether.config.diagnostic.ConfigDiagnostics;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Thread-safe atomic configuration handle supporting non-destructive reload, rollback on error, and change listeners.
 */
public final class ConfigHandle<T> {

    private final ConfigManager manager;
    private final Class<T> type;
    private final Path file;
    private final @Nullable ResourceProvider resourceProvider;
    private final @Nullable String defaultResourcePath;
    private final @Nullable Object hookContext;

    private final AtomicReference<T> snapshot;
    private final AtomicReference<ConfigDiagnostics> lastDiagnostics;
    private final CopyOnWriteArrayList<BiConsumer<T, T>> reloadListeners = new CopyOnWriteArrayList<>();
    private final Object reloadLock = new Object();

    ConfigHandle(
            ConfigManager manager,
            Class<T> type,
            Path file,
            @Nullable ResourceProvider resourceProvider,
            @Nullable String defaultResourcePath,
            @Nullable Object hookContext,
            T initialValue,
            ConfigDiagnostics initialDiagnostics
    ) {
        this.manager = manager;
        this.type = type;
        this.file = file;
        this.resourceProvider = resourceProvider;
        this.defaultResourcePath = defaultResourcePath;
        this.hookContext = hookContext;
        this.snapshot = new AtomicReference<>(initialValue);
        this.lastDiagnostics = new AtomicReference<>(initialDiagnostics);
    }

    public T get() {
        return snapshot.get();
    }

    public Path file() {
        return file;
    }

    public ConfigDiagnostics lastDiagnostics() {
        return lastDiagnostics.get();
    }

    public ConfigHandle<T> onReload(BiConsumer<T, T> listener) {
        reloadListeners.add(listener);
        return this;
    }

    /**
     * Reloads from disk atomically. If parsing or strict validation fails, the previous valid snapshot is retained.
     */
    public ReloadResult<T> reload() {
        synchronized (reloadLock) {
            T previous = snapshot.get();
            ConfigManager.LoadOutcome<T> outcome = manager.loadInternal(
                    type, file, resourceProvider, defaultResourcePath, hookContext
            );
            lastDiagnostics.set(outcome.diagnostics());
            if (!outcome.applied() || outcome.value() == null) {
                return new ReloadResult<>(false, false, previous, outcome.diagnostics());
            }
            T next = outcome.value();
            snapshot.set(next);
            for (BiConsumer<T, T> listener : reloadListeners) {
                listener.accept(previous, next);
            }
            return new ReloadResult<>(true, true, next, outcome.diagnostics());
        }
    }

    public ReloadResult<T> smartReload() {
        synchronized (reloadLock) {
            if (manager.fileCache().isFresh(file)) {
                return new ReloadResult<>(true, false, snapshot.get(), new ConfigDiagnostics());
            }
            return reload();
        }
    }

    public CompletableFuture<ReloadResult<T>> reloadAsync() {
        return CompletableFuture.supplyAsync(this::reload);
    }

    public void save() throws IOException {
        save(snapshot.get());
    }

    public void save(T value) throws IOException {
        synchronized (reloadLock) {
            manager.saveObject(value, file);
            snapshot.set(value);
        }
    }
}
