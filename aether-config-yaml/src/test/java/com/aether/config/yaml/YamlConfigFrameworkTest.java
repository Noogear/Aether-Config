package com.aether.config.yaml;

import com.aether.config.annotation.Check;
import com.aether.config.annotation.Comment;
import com.aether.config.annotation.Default;
import com.aether.config.annotation.DefaultResources;
import com.aether.config.annotation.Header;
import com.aether.config.annotation.Ignore;
import com.aether.config.annotation.Inline;
import com.aether.config.annotation.Key;
import com.aether.config.annotation.NonBlank;
import com.aether.config.annotation.Pattern;
import com.aether.config.annotation.PostLoad;
import com.aether.config.annotation.PreLoad;
import com.aether.config.annotation.Range;
import com.aether.config.annotation.Version;
import com.aether.config.bind.SyncMode;
import com.aether.config.bind.UnknownKeyPolicy;
import com.aether.config.core.ConfigHandle;
import com.aether.config.core.ConfigManager;
import com.aether.config.core.DirectoryConfig;
import com.aether.config.core.ReloadResult;
import com.aether.config.diagnostic.ConfigDecodeException;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.interpolate.InterpolationMode;
import com.aether.config.migration.MigrationChain;
import com.aether.config.view.ConfigSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class YamlConfigFrameworkTest {

    public static class NonEmptyEvenValidator implements Check.ConfigValidator<Integer> {
        @Override
        public Integer validate(Integer value) throws ConfigDecodeException {
            if (value == null || value % 2 != 0) {
                throw new ConfigDecodeException("expected even integer, got " + value);
            }
            return value;
        }
    }

    @Header({"AetherConfig Main Settings", "Auto-generated header"})
    @Version(value = 2, key = "config-version", autoBackup = true)
    public record AppConfig(
            @Comment("Database connection settings")
            DatabaseSettings database,

            @Comment("Tick interval for periodic tasks")
            @Inline("10 ticks = 0.5s")
            @Range(min = 1, max = 72000, clamp = true)
            @Default("10")
            int suctionIntervalTicks,

            @Default("30s")
            Duration flushTimeout,

            @NonBlank
            @Pattern("^[a-z0-9_]+$")
            @Default("aether_")
            String tablePrefix,

            @Check(NonEmptyEvenValidator.class)
            @Default("4")
            int workerThreads,

            @Key("prices")
            ConfigSection dynamicPrices
    ) {
        public record DatabaseSettings(
                @NonBlank @Default("localhost") String host,
                @Range(min = 1, max = 65535, clamp = false) @Default("3306") int port
        ) {
        }
    }

    public static class ChestEntry {
        public String displayName = "Basic Chest";
        @Range(min = 1, max = 6, clamp = true)
        public int rows = 3;
        @Ignore
        public transient int loadHookCount = 0;

        @PreLoad
        void onPreLoad() {
            loadHookCount++;
        }

        @PostLoad
        void onPostLoad(List<String> log) {
            if (log != null) {
                log.add(displayName + ":" + rows);
            }
        }
    }

    @DefaultResources({"chests/starter.yml"})
    public record DirectoryProfile(
            @Default("CHEST") String type,
            @Range(min = 1, max = 6, clamp = true) @Default("3") int rows
    ) {
    }

    @Test
    void generatesDefaultsPatchesMissingKeysAndPreservesUserComments(@TempDir Path tempDir) throws Exception {
        Path cfgFile = tempDir.resolve("config.yml");
        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .syncMode(SyncMode.PATCH_MISSING)
                .unknownKeys(UnknownKeyPolicy.WARN)
                .build();

        // 1. Initial load creates annotated YAML with @Header, @Comment, @Inline, @Version
        ConfigHandle<AppConfig> handle = manager.load(AppConfig.class, cfgFile);
        AppConfig initial = handle.get();
        assertEquals("localhost", initial.database().host());
        assertEquals(3306, initial.database().port());
        assertEquals(10, initial.suctionIntervalTicks());
        assertEquals(Duration.ofSeconds(30), initial.flushTimeout());
        assertEquals("aether_", initial.tablePrefix());
        assertEquals(4, initial.workerThreads());

        String generatedYaml = Files.readString(cfgFile, StandardCharsets.UTF_8);
        assertTrue(generatedYaml.contains("# AetherConfig Main Settings"));
        assertTrue(generatedYaml.contains("# Database connection settings"));
        assertTrue(generatedYaml.contains("# 10 ticks = 0.5s"));
        assertTrue(generatedYaml.contains("config-version: 2"));

        // 2. Simulate user editing file: custom comment + deleting flush-timeout + out-of-range suction + typo key
        String edited = """
                # User kept header
                config-version: 2
                # My custom database note
                database:
                  host: prod-db.local
                  port: 5432
                suction-interval-ticks: 999999
                table-prefix: prod_
                worker-threads: 6
                typo-setting: true
                prices:
                  DIAMOND: 250.0
                """;
        Files.writeString(cfgFile, edited, StandardCharsets.UTF_8);

        ReloadResult<AppConfig> reloaded = handle.reload();
        assertTrue(reloaded.success());
        AppConfig cfg = reloaded.value();
        assertEquals("prod-db.local", cfg.database().host());
        assertEquals(5432, cfg.database().port());
        assertEquals(72000, cfg.suctionIntervalTicks()); // clamped
        assertEquals(Duration.ofSeconds(30), cfg.flushTimeout()); // restored default
        assertEquals(250.0, cfg.dynamicPrices().getDouble("DIAMOND", 0.0));

        // Check diagnostics include line numbers & spelling hint
        String diagText = reloaded.diagnostics().formatAll();
        assertTrue(diagText.contains("suction-interval-ticks"));
        assertTrue(diagText.contains("typo-setting"));

        // Patched YAML should now contain flush-timeout while preserving "# My custom database note"
        String patchedYaml = Files.readString(cfgFile, StandardCharsets.UTF_8);
        assertTrue(patchedYaml.contains("# My custom database note"));
        assertTrue(patchedYaml.contains("flush-timeout: 30s"));
        assertTrue(patchedYaml.contains("typo-setting: true")); // preserved under WARN policy
    }

    @Test
    void atomicReloadRollsBackOnBrokenSyntaxAndNotifiesListenersOnValidUpdate(@TempDir Path tempDir) throws Exception {
        Path cfgFile = tempDir.resolve("config.yml");
        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .strictMode(false)
                .build();

        ConfigHandle<AppConfig> handle = manager.load(AppConfig.class, cfgFile);
        AtomicInteger callbackCount = new AtomicInteger();
        handle.onReload((oldCfg, newCfg) -> callbackCount.incrementAndGet());

        // Break YAML syntax on disk
        Files.writeString(cfgFile, "database:\n  host: [unclosed\n", StandardCharsets.UTF_8);
        ReloadResult<AppConfig> badReload = handle.reload();

        assertFalse(badReload.success());
        assertFalse(badReload.changed());
        assertTrue(badReload.diagnostics().hasErrors());
        assertEquals(DiagnosticCategory.SYNTAX, badReload.diagnostics().entries().get(0).category());
        // Previous valid snapshot MUST remain untouched
        assertEquals("localhost", handle.get().database().host());
        assertEquals(0, callbackCount.get());

        // Fix YAML syntax and reload again
        Files.writeString(cfgFile, """
                config-version: 2
                database:
                  host: recovered.local
                  port: 3307
                """, StandardCharsets.UTF_8);
        ReloadResult<AppConfig> goodReload = handle.reload();
        assertTrue(goodReload.success());
        assertTrue(goodReload.changed());
        assertEquals("recovered.local", handle.get().database().host());
        assertEquals(1, callbackCount.get());
    }

    @Test
    void versionMigrationRunsFunctionalStepsCreatesBackupAndPreservesComments(@TempDir Path tempDir) throws Exception {
        Path cfgFile = tempDir.resolve("config.yml");
        Files.writeString(cfgFile, """
                config-version: 1
                # User comment above old host
                old-db-host: legacy.example.com
                tick-interval: 25
                table-prefix: v1_
                worker-threads: 4
                """, StandardCharsets.UTF_8);

        MigrationChain migrations = MigrationChain.builder()
                .step(1, 2, doc -> {
                    doc.rename("old-db-host", "database.host");
                    doc.rename("tick-interval", "suction-interval-ticks");
                })
                .build();

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .migrations(AppConfig.class, migrations)
                .build();

        ConfigHandle<AppConfig> handle = manager.load(AppConfig.class, cfgFile);
        assertEquals("legacy.example.com", handle.get().database().host());
        assertEquals(25, handle.get().suctionIntervalTicks());
        assertTrue(handle.lastDiagnostics().entries().stream()
                .anyMatch(e -> e.category() == DiagnosticCategory.MIGRATION));

        // Backup created under backups/
        Path backupDir = tempDir.resolve("backups");
        assertTrue(Files.isDirectory(backupDir));
        try (var stream = Files.list(backupDir)) {
            assertEquals(1, stream.count());
        }

        // File migrated to v2
        String migratedYaml = Files.readString(cfgFile, StandardCharsets.UTF_8);
        assertTrue(migratedYaml.contains("config-version: 2"));
        assertTrue(migratedYaml.contains("legacy.example.com"));
        assertFalse(migratedYaml.contains("old-db-host"));
    }

    @Test
    void interpolationResolvesEnvAtRuntimeWithoutExposingSecretOnDisk(@TempDir Path tempDir) throws Exception {
        Path cfgFile = tempDir.resolve("config.yml");
        Files.writeString(cfgFile, """
                config-version: 2
                database:
                  host: ${env:AETHER_DB_HOST:fallback.local}
                  port: 3306
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .interpolation(InterpolationMode.ALL)
                .envLookup(key -> "AETHER_DB_HOST".equals(key) ? "secret-prod-host" : null)
                .syncMode(SyncMode.PATCH_MISSING)
                .build();

        ConfigHandle<AppConfig> handle = manager.load(AppConfig.class, cfgFile);
        assertEquals("secret-prod-host", handle.get().database().host());

        // Disk file MUST still retain the ${env:...} placeholder after PATCH_MISSING
        String disk = Files.readString(cfgFile, StandardCharsets.UTF_8);
        assertTrue(disk.contains("${env:AETHER_DB_HOST:fallback.local}"));
        assertFalse(disk.contains("secret-prod-host"));
    }

    @Test
    void directoryConfigSupportsReflectionFactoryDuplicatePolicyAndIncrementalReload(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("chests");
        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .build();

        // 1. Reflection mode with @DefaultResources + ERROR_AND_SKIP duplicate policy
        DirectoryConfig<DirectoryProfile> profiles = manager.directory(dir)
                .bind(DirectoryProfile.class)
                .rootKey("chests")
                .recursive()
                .duplicatePolicy(DirectoryConfig.DuplicateKeyPolicy.ERROR_AND_SKIP)
                .defaults(path -> switch (path) {
                    case "chests/starter.yml" -> new ByteArrayInputStream("""
                            chests:
                              starter:
                                type: CHEST
                                rows: 4
                            """.getBytes(StandardCharsets.UTF_8));
                    default -> null;
                })
                .load();

        assertEquals(1, profiles.all().size());
        assertEquals(4, profiles.get("starter").rows());
        assertNull(profiles.get("nonexistent"));

        // Add duplicate in nested file -> skipped with ERROR diagnostic
        Path nested = Files.createDirectories(dir.resolve("sub"));
        Path extraFile = nested.resolve("extra.yml");
        Files.writeString(extraFile, """
                chests:
                  starter:
                    type: STORAGE_UNIT
                    rows: 6
                  advanced:
                    type: CHEST
                    rows: 99
                """, StandardCharsets.UTF_8);

        ReloadResult<Map<String, DirectoryProfile>> reload1 = profiles.reload();
        assertTrue(reload1.changed());
        assertTrue(reload1.diagnostics().hasErrors());
        assertEquals(4, profiles.get("starter").rows()); // kept first
        assertEquals(6, profiles.get("advanced").rows()); // clamped from 99 -> 6

        // Update extra.yml & bump mtime -> incremental reload picks it up
        Files.writeString(extraFile, """
                chests:
                  advanced:
                    type: CHEST
                    rows: 5
                """, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(extraFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
        ReloadResult<Map<String, DirectoryProfile>> reload2 = profiles.reload();
        assertTrue(reload2.changed());
        assertFalse(reload2.diagnostics().hasErrors());
        assertEquals(5, profiles.get("advanced").rows());

        // 2. Factory mode + POJO hooks
        List<String> hookLogs = new ArrayList<>();
        Map<String, ChestEntry> pojoMap = new LinkedHashMap<>();
        DirectoryConfig<ChestEntry> custom = manager.directory(dir)
                .factory((id, sec) -> {
                    ChestEntry e = manager.binder().bind(ChestEntry.class, sec, null, hookLogs);
                    pojoMap.put(id, e);
                    return e;
                })
                .rootKey("chests")
                .recursive()
                .load();

        assertTrue(custom.all().containsKey("starter"));
        assertEquals(1, custom.get("starter").loadHookCount);
        assertFalse(hookLogs.isEmpty());
    }

    @Test
    void saveObjectUpdatesValuesWhilePreservingYamlComments(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("pojo.yml");
        Files.writeString(file, """
                # Custom file header
                # My comment for display name
                display-name: OldName
                rows: 3
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .build();
        ConfigHandle<ChestEntry> handle = manager.load(ChestEntry.class, file);

        ChestEntry updated = new ChestEntry();
        updated.displayName = "NewName";
        updated.rows = 6;
        handle.save(updated);

        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("display-name: NewName"));
        assertTrue(saved.contains("rows: 6"));
        assertTrue(saved.contains("# My comment for display name"));
        assertTrue(saved.contains("# Custom file header"));
    }

    @Test
    void duplicateYamlKeysReportExactLineError() {
        ConfigDiagnostics diag = new ConfigDiagnostics();
        YamlFormat format = YamlFormat.create();
        String yaml = """
                host: a
                port: 3306
                host: b
                """;
        ConfigManager manager = ConfigManager.builder()
                .format(format)
                .build();
        ConfigSection sec = manager.parseSection("dup.yml", new java.io.StringReader(yaml), diag);

        assertEquals("a", sec.getString("host", null));
        assertTrue(diag.hasErrors());
        String formatted = diag.formatAll();
        assertTrue(formatted.contains("dup.yml:3:1"));
        assertTrue(formatted.contains("Duplicate configuration key 'host'"));
    }

    @Test
    void validationAnnotationsRejectInvalidValuesAndStrictModeThrows(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("bad.yml");
        Files.writeString(file, """
                config-version: 2
                database:
                  host: "   "
                  port: 99999
                table-prefix: "INVALID-PREFIX!"
                worker-threads: 3
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .strictMode(true)
                .build();

        com.aether.config.diagnostic.ConfigException ex = assertThrows(
                com.aether.config.diagnostic.ConfigException.class,
                () -> manager.load(AppConfig.class, file)
        );

        String report = ex.diagnostics().formatAll();
        assertEquals(4, ex.diagnostics().errorCount());
        assertTrue(report.contains("database.host"));
        assertTrue(report.contains("database.port"));
        assertTrue(report.contains("table-prefix"));
        assertTrue(report.contains("worker-threads"));
    }

    @Test
    void smartReloadSkipsUnchangedFileAndYamlMergeAnchorsWork(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("anchors.yml");
        Files.writeString(file, """
                config-version: 2
                base: &db_defaults
                  host: anchored.local
                  port: 5433
                database:
                  <<: *db_defaults
                  port: 5439
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .unknownKeys(UnknownKeyPolicy.IGNORE)
                .syncMode(SyncMode.READ_ONLY)
                .build();

        ConfigHandle<AppConfig> handle = manager.load(AppConfig.class, file);
        assertEquals("anchored.local", handle.get().database().host());
        assertEquals(5439, handle.get().database().port());

        ReloadResult<AppConfig> noop = handle.smartReload();
        assertTrue(noop.success());
        assertFalse(noop.changed());
    }
}
