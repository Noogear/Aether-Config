package com.aether.config.migration;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Creates timestamped backups of configuration files before destructive or schema-migrating writes.
 */
public final class ConfigBackup {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ConfigBackup() {
    }

    public static @Nullable Path backup(Path sourceFile, @Nullable String label) throws IOException {
        if (Files.notExists(sourceFile)) {
            return null;
        }
        Path parent = sourceFile.getParent();
        Path backupDir = parent != null ? parent.resolve("backups") : Path.of("backups");
        Files.createDirectories(backupDir);

        String fileName = sourceFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        String tag = label != null && !label.isBlank() ? "_" + label.trim() : "";
        String timestamp = LocalDateTime.now().format(TS_FORMAT);

        Path target = backupDir.resolve(stem + tag + "_" + timestamp + ext);
        int counter = 1;
        while (Files.exists(target)) {
            target = backupDir.resolve(stem + tag + "_" + timestamp + "_" + counter + ext);
            counter++;
        }
        Files.copy(sourceFile, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }
}
