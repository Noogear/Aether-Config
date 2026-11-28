package com.aether.config.yaml;

import com.aether.config.annotation.Comment;
import com.aether.config.annotation.Key;
import com.aether.config.annotation.Polymorphic;
import com.aether.config.annotation.Version;
import com.aether.config.bind.SyncMode;
import com.aether.config.codec.ConfigCodec;
import com.aether.config.core.ConfigHandle;
import com.aether.config.core.ConfigManager;
import com.aether.config.core.DirectoryConfig;
import com.aether.config.core.ReloadResult;
import com.aether.config.core.ResourceProvider;
import com.aether.config.migration.MigrationChain;
import com.aether.config.node.ConfigNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UpgradeFeaturesTest {

    record CustomBadge(String id) {
    }

    @Version(value = 3, autoBackup = false)
    record MigrationSample(int configVersion, String title) {
    }

    public static class PojoParent {
        public PojoChild nested = new PojoChild("preset-name", 42);
    }

    public static class PojoChild {
        public String name = "default";
        public int count = 1;

        public PojoChild() {
        }

        public PojoChild(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    record DottedAndMapConfig(
            @Key("aether.flag") boolean enabled,
            @Comment({"Map entries"})
            Map<String, String> labels
    ) {
    }

    @Polymorphic(typeKey = "kind", value = {
            @Polymorphic.SubType(name = "grant", type = GrantRule.class),
            @Polymorphic.SubType(name = "limit", type = LimitRule.class)
    })
    public sealed interface ActionRule permits GrantRule, LimitRule {
    }

    public record GrantRule(String permission) implements ActionRule {
    }

    public record LimitRule(int maxAmount) implements ActionRule {
    }

    record RuleHolder(List<ActionRule> rules) {
    }

    record RoleTemplate(
            @Version(value = 1, autoBackup = false) int version,
            String displayName,
            Map<String, Boolean> permissions
    ) {
    }

    record PartialRecoveryHolder(List<Integer> numbers, Map<String, Integer> scores) {
    }

    @Test
    void forkIsolatesChildCodecsFromParent() {
        ConfigManager parent = ConfigManager.builder()
                .format(YamlFormat.create())
                .build();

        ConfigManager child = parent.fork()
                .registerCodec(CustomBadge.class, ConfigCodec.of(
                        v -> new CustomBadge(v.asString("none")),
                        (b, ctx) -> ConfigNode.ScalarNode.of(b.id())
                ))
                .build();

        assertTrue(child.codecs().hasExplicit(CustomBadge.class));
        assertFalse(parent.codecs().hasExplicit(CustomBadge.class));
    }

    @Test
    void directoryConfigFileAsEntryPreservesSubdirsAndSupportsSaveAndReload(@TempDir Path tempDir) throws Exception {
        ResourceProvider provider = path -> switch (path) {
            case "templates/roles/builder.yml" -> new ByteArrayInputStream("""
                    version: 1
                    display-name: Builder
                    permissions:
                      block_break: true
                    """.getBytes(StandardCharsets.UTF_8));
            case "templates/roles/guest.yml" -> new ByteArrayInputStream("""
                    version: 1
                    display-name: Guest
                    permissions:
                      block_break: false
                    """.getBytes(StandardCharsets.UTF_8));
            default -> null;
        };

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .syncMode(SyncMode.PATCH_MISSING)
                .build();

        DirectoryConfig<RoleTemplate> dir = manager.directory(tempDir)
                .bind(RoleTemplate.class)
                .recursive()
                .fileAsEntry(true)
                .resourcePrefix("templates/")
                .defaults(provider, DirectoryConfig.DefaultSeedPolicy.FILL_MISSING,
                        "templates/roles/builder.yml", "templates/roles/guest.yml")
                .load();

        assertTrue(Files.exists(tempDir.resolve("roles/builder.yml")));
        assertEquals("Builder", dir.get("roles/builder").displayName());
        assertEquals(Boolean.FALSE, dir.get("roles/guest").permissions().get("block_break"));
        assertEquals(tempDir.resolve("roles/builder.yml").toAbsolutePath().normalize(), dir.sourceFile("roles/builder"));

        AtomicInteger reloads = new AtomicInteger();
        dir.onReload((prev, next) -> reloads.incrementAndGet());

        dir.saveEntry("roles/builder", new RoleTemplate(1, "Master Builder", Map.of("block_break", true, "invite", true)));
        assertEquals(1, reloads.get());
        assertEquals("Master Builder", dir.get("roles/builder").displayName());
    }

    @Test
    void saveObjectPrunesDeletedMapKeysAndPreservesUserComments(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("cfg.yml");
        Files.writeString(file, """
                # Custom user comment on flag
                aether.flag: true
                labels:
                  alpha: one
                  beta: two
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .build();

        ConfigHandle<DottedAndMapConfig> handle = manager.load(DottedAndMapConfig.class, file);
        assertTrue(handle.get().enabled());
        assertEquals(2, handle.get().labels().size());

        Map<String, String> updated = new LinkedHashMap<>();
        updated.put("alpha", "one-updated");
        handle.save(new DottedAndMapConfig(false, updated));

        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("# Custom user comment on flag"));
        assertTrue(saved.contains("alpha: one-updated"));
        assertFalse(saved.contains("beta:"));
    }

    @Test
    void configHandleAsyncReloadIsolatesFailingListeners(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("async.yml");
        Files.writeString(file, """
                aether.flag: true
                labels: {}
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .build();
        ConfigHandle<DottedAndMapConfig> handle = manager.load(DottedAndMapConfig.class, file);

        AtomicBoolean secondCalled = new AtomicBoolean(false);
        handle.onReload((prev, next) -> {
            throw new RuntimeException("boom");
        });
        handle.onReload((prev, next) -> secondCalled.set(true));

        Files.writeString(file, """
                aether.flag: false
                labels:
                  k: v
                """, StandardCharsets.UTF_8);

        try (var exec = Executors.newSingleThreadExecutor()) {
            ReloadResult<DottedAndMapConfig> res = handle.reloadAsync(exec).get();
            assertTrue(res.success());
            assertTrue(res.changed());
            assertFalse(res.value().enabled());
            assertTrue(secondCalled.get());
            assertTrue(res.diagnostics().hasWarnings());
        }
    }

    @Test
    void missingMigrationHopWarnsAndDoesNotSkipTargetVersion(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("mig.yml");
        Files.writeString(file, """
                config-version: 1
                title: Old
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .migrations(MigrationSample.class, MigrationChain.builder()
                        .step(1, 2, doc -> doc.setRaw("title", "V2"))
                        // intentionally missing 2 -> 3
                        .build())
                .build();

        ConfigHandle<MigrationSample> handle = manager.load(MigrationSample.class, file);
        assertEquals("V2", handle.get().title());
        assertTrue(handle.lastDiagnostics().hasWarnings());
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("config-version: 2"));
        assertFalse(saved.contains("config-version: 3"));
    }

    @Test
    void polymorphicSealedAndPojoDefaultsAndPartialCollectionRecovery(@TempDir Path tempDir) throws Exception {
        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .build();

        Path polyFile = tempDir.resolve("rules.yml");
        Files.writeString(polyFile, """
                rules:
                  - kind: grant
                    permission: aethersky.fly
                  - kind: limit
                    max-amount: 16
                """, StandardCharsets.UTF_8);

        ConfigHandle<RuleHolder> polyHandle = manager.load(RuleHolder.class, polyFile);
        assertEquals(2, polyHandle.get().rules().size());
        assertInstanceOf(GrantRule.class, polyHandle.get().rules().get(0));
        assertInstanceOf(LimitRule.class, polyHandle.get().rules().get(1));
        polyHandle.save();
        assertTrue(Files.readString(polyFile, StandardCharsets.UTF_8).contains("kind: grant"));

        Path pojoFile = tempDir.resolve("pojo.yml");
        Files.writeString(pojoFile, "{}\n", StandardCharsets.UTF_8);
        ConfigHandle<PojoParent> pojoHandle = manager.load(PojoParent.class, pojoFile);
        assertEquals("preset-name", pojoHandle.get().nested.name);
        assertEquals(42, pojoHandle.get().nested.count);

        Path partialFile = tempDir.resolve("partial.yml");
        Files.writeString(partialFile, """
                numbers:
                  - 10
                  - not-a-number
                  - 30
                scores:
                  alice: 100
                  bob: bad
                """, StandardCharsets.UTF_8);
        ConfigHandle<PartialRecoveryHolder> partialHandle = manager.load(PartialRecoveryHolder.class, partialFile);
        assertEquals(List.of(10, 30), partialHandle.get().numbers());
        assertEquals(Map.of("alice", 100), partialHandle.get().scores());
        assertTrue(partialHandle.lastDiagnostics().hasErrors());

        assertEquals(Locale.SIMPLIFIED_CHINESE,
                manager.codecs().find(Locale.class).orElseThrow()
                        .decode(com.aether.config.view.ConfigSection.ofMap("l", Map.of("l", "zh_CN"),
                                new com.aether.config.diagnostic.ConfigDiagnostics(), manager.codecs()).value("l")));
    }
}
