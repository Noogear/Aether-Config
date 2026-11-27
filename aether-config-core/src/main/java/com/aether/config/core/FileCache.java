package com.aether.config.core;

import com.aether.config.format.ConfigFormat;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Thread-safe file freshness & content cache keyed by normalized {@link Path}.
 */
public final class FileCache {

    public record Entry(long lastModifiedMillis, long size, String content) {
        public boolean matches(Path path) {
            try {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                return Files.getLastModifiedTime(path).toMillis() == lastModifiedMillis
                        && Files.size(path) == size;
            } catch (IOException ex) {
                return false;
            }
        }
    }

    private final Map<Path, Entry> entries = new ConcurrentHashMap<>();

    public boolean isFresh(Path file) {
        Path norm = file.toAbsolutePath().normalize();
        Entry entry = entries.get(norm);
        return entry != null && entry.matches(norm);
    }

    public @Nullable String getCachedContent(Path file) {
        Entry entry = entries.get(file.toAbsolutePath().normalize());
        return entry != null ? entry.content() : null;
    }

    public String read(Path file) throws IOException {
        Path norm = file.toAbsolutePath().normalize();
        String content = Files.readString(norm, StandardCharsets.UTF_8);
        long mtime = Files.getLastModifiedTime(norm).toMillis();
        long size = Files.size(norm);
        entries.put(norm, new Entry(mtime, size, content));
        return content;
    }

    public @Nullable String readIfChanged(Path file) throws IOException {
        if (isFresh(file)) {
            return null;
        }
        return read(file);
    }

    public void put(Path file, String content) throws IOException {
        Path norm = file.toAbsolutePath().normalize();
        long mtime = Files.isRegularFile(norm) ? Files.getLastModifiedTime(norm).toMillis() : 0L;
        long size = Files.isRegularFile(norm) ? Files.size(norm) : content.getBytes(StandardCharsets.UTF_8).length;
        entries.put(norm, new Entry(mtime, size, content));
    }

    public boolean isDirectoryFresh(Path directory, boolean recursive, ConfigFormat format) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        List<Path> live = listMatchingFiles(directory, recursive, format);
        Path normDir = directory.toAbsolutePath().normalize();
        long cachedCount = entries.keySet().stream()
                .filter(p -> p.startsWith(normDir))
                .count();
        if (live.size() != cachedCount) {
            return false;
        }
        for (Path p : live) {
            if (!isFresh(p)) {
                return false;
            }
        }
        return true;
    }

    public void purgeStale(Path directory, boolean recursive, ConfigFormat format) {
        Path normDir = directory.toAbsolutePath().normalize();
        List<Path> live = Files.isDirectory(normDir) ? listMatchingFiles(normDir, recursive, format) : List.of();
        entries.keySet().removeIf(k -> k.startsWith(normDir) && !live.contains(k));
    }

    public static List<Path> listMatchingFiles(Path directory, boolean recursive, ConfigFormat format) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> walk = Files.walk(directory, maxDepth)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> format.supportsFileName(p.getFileName().toString()))
                    .map(p -> p.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(p -> directory.toAbsolutePath().normalize().relativize(p).toString().replace('\\', '/')))
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public void clear() {
        entries.clear();
    }
}
