package com.aether.config.paper;

import com.aether.config.annotation.Default;
import com.aether.config.core.ConfigHandle;
import com.aether.config.core.ConfigManager;
import com.aether.config.yaml.YamlFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PaperCodecsTest {

    public record VisualSettings(
            @Default("CHEST") Material itemMaterial,
            @Default("FLAME") Particle particle,
            @Default("aether:storage_unit") NamespacedKey modelKey,
            @Default("<aqua>Aether Chest") Component title,
            @Default("#6600FF00") Color backgroundColor,
            @Default("#FFAA00") TextColor accentColor
    ) {
    }

    @Test
    void decodesAndEncodesPaperAndAdventureTypes(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("visuals.yml");
        Files.writeString(file, """
                item-material: diamond_block
                particle: soul_fire_flame
                model-key: custom:my_chest
                title: '<gold>Collector'
                background-color: '#80112233'
                accent-color: '#00FFAA'
                """, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.builder()
                .format(YamlFormat.create())
                .codecs(PaperCodecs::registerDefaults)
                .build();

        ConfigHandle<VisualSettings> handle = manager.load(VisualSettings.class, file);
        VisualSettings settings = handle.get();

        assertEquals(Material.DIAMOND_BLOCK, settings.itemMaterial());
        assertEquals(Particle.SOUL_FIRE_FLAME, settings.particle());
        assertEquals(NamespacedKey.fromString("custom:my_chest"), settings.modelKey());
        assertEquals("<gold>Collector", MiniMessage.miniMessage().serialize(settings.title()));
        assertEquals(Color.fromARGB(0x80, 0x11, 0x22, 0x33), settings.backgroundColor());
        assertEquals(TextColor.color(0x00FFAA), settings.accentColor());

        handle.save(new VisualSettings(
                Material.BARREL,
                Particle.FLAME,
                NamespacedKey.fromString("aether:barrel"),
                MiniMessage.miniMessage().deserialize("<aqua>Barrel"),
                Color.fromARGB(0),
                TextColor.color(0xFFFFFF)
        ));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("item-material: BARREL"));
        assertTrue(written.contains("model-key: aether:barrel"));
        assertTrue(written.contains("accent-color: '#ffffff'") || written.contains("accent-color: '#FFFFFF'"));
    }
}
