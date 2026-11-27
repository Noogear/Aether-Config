package com.aether.config.diagnostic;

import com.aether.config.node.SourceSpan;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Thread-safe diagnostic collector aggregating errors, warnings, and info messages across config loading.
 */
public final class ConfigDiagnostics {

    private final List<DiagnosticEntry> entries = new CopyOnWriteArrayList<>();

    public void add(DiagnosticEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    public void addAll(ConfigDiagnostics other) {
        if (other != null) {
            entries.addAll(other.entries);
        }
    }

    public void info(DiagnosticCategory category, SourceSpan span, String path, String message) {
        entries.add(new DiagnosticEntry(DiagnosticSeverity.INFO, category, span, path, message, null));
    }

    public void warn(String source, String path, String message) {
        warn(DiagnosticCategory.VALIDATION, SourceSpan.ofSource(source), path, message, null);
    }

    public void warn(DiagnosticCategory category, SourceSpan span, String path, String message) {
        warn(category, span, path, message, null);
    }

    public void warn(DiagnosticCategory category, SourceSpan span, String path, String message, @Nullable String hint) {
        entries.add(new DiagnosticEntry(DiagnosticSeverity.WARN, category, span, path, message, hint));
    }

    public void error(String source, String path, String message) {
        error(DiagnosticCategory.TYPE, SourceSpan.ofSource(source), path, message, null);
    }

    public void error(DiagnosticCategory category, SourceSpan span, String path, String message) {
        error(category, span, path, message, null);
    }

    public void error(DiagnosticCategory category, SourceSpan span, String path, String message, @Nullable String hint) {
        entries.add(new DiagnosticEntry(DiagnosticSeverity.ERROR, category, span, path, message, hint));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean hasWarnings() {
        return entries.stream().anyMatch(e -> e.severity() == DiagnosticSeverity.WARN);
    }

    public boolean hasErrors() {
        return entries.stream().anyMatch(e -> e.severity() == DiagnosticSeverity.ERROR);
    }

    public long warningCount() {
        return entries.stream().filter(e -> e.severity() == DiagnosticSeverity.WARN).count();
    }

    public long errorCount() {
        return entries.stream().filter(e -> e.severity() == DiagnosticSeverity.ERROR).count();
    }

    public List<DiagnosticEntry> entries() {
        return List.copyOf(entries);
    }

    public String formatAll() {
        return entries.stream().map(DiagnosticEntry::format).collect(Collectors.joining("\n"));
    }

    public void flush(Consumer<DiagnosticEntry> sink) {
        for (DiagnosticEntry entry : entries) {
            sink.accept(entry);
        }
    }

    public void flush(Logger logger) {
        for (DiagnosticEntry entry : entries) {
            switch (entry.severity()) {
                case ERROR -> logger.severe(entry.format());
                case WARN -> logger.warning(entry.format());
                case INFO -> logger.info(entry.format());
            }
        }
    }
}
