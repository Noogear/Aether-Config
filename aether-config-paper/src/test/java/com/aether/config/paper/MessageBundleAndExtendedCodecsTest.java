package com.aether.config.paper;

import com.aether.config.core.ConfigManager;
import com.aether.config.core.ResourceProvider;
import com.aether.config.yaml.YamlFormat;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class MessageBundleAndExtendedCodecsTest {

    record ExtendedTypes(
            MessageTemplate greeting,
            Key namespaceKey,
            Vector offset,
            BlockVector spawnPoint,
            GameMode gameMode,
            Difficulty difficulty,
            World.Environment environment,
            EntityType entityType
    ) {
    }

    @Test
    void decodesExtendedPaperTypesAndMessageTemplates(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("ext.yml");
        Files.writeString(file, """
                greeting: '<green>Welcome <player> to island <island>!'
                namespace-key: 'aethersky:block_break'
                offset: '1.5,64.0,-2.5'
                spawn-point:
                  x: 0
                  y: 100
                  z: 0
                game-mode: survival
                difficulty: hard
                environment: normal
                entity-type: creeper
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .codecs(PaperCodecs::registerDefaults)
                .build();

        ExtendedTypes cfg = manager.load(ExtendedTypes.class, file).get();
        assertEquals("<green>Welcome <player> to island <island>!", cfg.greeting().raw());
        String rendered = PlainTextComponentSerializer.plainText().serialize(
                cfg.greeting().render(
                        Placeholder.unparsed("player", "Alex"),
                        Placeholder.unparsed("island", "SkyHome")
                )
        );
        assertEquals("Welcome Alex to island SkyHome!", rendered);
        assertEquals(Key.key("aethersky:block_break"), cfg.namespaceKey());
        assertEquals(new Vector(1.5, 64.0, -2.5), cfg.offset());
        assertEquals(new BlockVector(0, 100, 0), cfg.spawnPoint());
        assertEquals(GameMode.SURVIVAL, cfg.gameMode());
        assertEquals(Difficulty.HARD, cfg.difficulty());
        assertEquals(World.Environment.NORMAL, cfg.environment());
        assertEquals(EntityType.CREEPER, cfg.entityType());
    }

    @Test
    void messageBundleResolvesFallbackChain(@TempDir Path tempDir) {
        ResourceProvider provider = path -> switch (path) {
            case "lang/en_US.yml" -> new ByteArrayInputStream("""
                    island:
                      created: '<green>Island created for <player>!'
                      only-en: '<yellow>Fallback English'
                    """.getBytes(StandardCharsets.UTF_8));
            case "lang/zh_CN.yml" -> new ByteArrayInputStream("""
                    island:
                      created: '<green>已为 <player> 创建空岛！'
                    """.getBytes(StandardCharsets.UTF_8));
            default -> null;
        };

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .codecs(PaperCodecs::registerDefaults)
                .build();

        MessageBundle bundle = MessageBundle.builder(manager, tempDir)
                .defaultLocale(Locale.US)
                .defaults(provider, "lang/en_US.yml", "lang/zh_CN.yml")
                .load();

        String zhTwRendered = PlainTextComponentSerializer.plainText().serialize(
                bundle.render(Locale.TRADITIONAL_CHINESE, "island.created", Placeholder.unparsed("player", "Steve"))
        );
        assertEquals("已为 Steve 创建空岛！", zhTwRendered);

        String fallbackRendered = PlainTextComponentSerializer.plainText().serialize(
                bundle.render(Locale.SIMPLIFIED_CHINESE, "island.only-en")
        );
        assertEquals("Fallback English", fallbackRendered);
    }
}
