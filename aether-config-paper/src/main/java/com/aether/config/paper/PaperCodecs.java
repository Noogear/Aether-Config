package com.aether.config.paper;

import com.aether.config.codec.CodecRegistry;
import com.aether.config.codec.ConfigCodec;
import com.aether.config.diagnostic.ConfigDecodeException;
import com.aether.config.node.ConfigNode;
import com.aether.config.view.ConfigValue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Paper / Bukkit / Adventure {@link ConfigCodec} suite decoupled from {@code aether-config-core}.
 */
public final class PaperCodecs {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private PaperCodecs() {
    }

    public static final ConfigCodec<Material> MATERIAL = ConfigCodec.of(
            val -> {
                String name = val.asNonBlankString();
                if (name == null) {
                    throw new ConfigDecodeException("missing material name");
                }
                Material mat = Material.matchMaterial(name);
                if (mat == null) {
                    throw new ConfigDecodeException("unknown Bukkit material '" + name + "'");
                }
                return mat;
            },
            (mat, ctx) -> ConfigNode.ScalarNode.of(mat.name())
    );

    public static final ConfigCodec<Particle> PARTICLE = ConfigCodec.of(
            val -> {
                String name = val.asNonBlankString();
                if (name == null) {
                    throw new ConfigDecodeException("missing particle type");
                }
                try {
                    return Particle.valueOf(name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw new ConfigDecodeException("unknown Bukkit particle '" + name + "'");
                }
            },
            (p, ctx) -> ConfigNode.ScalarNode.of(p.name())
    );

    public static final ConfigCodec<NamespacedKey> NAMESPACED_KEY = ConfigCodec.of(
            val -> {
                String raw = val.asNonBlankString();
                if (raw == null) {
                    throw new ConfigDecodeException("missing namespaced key");
                }
                NamespacedKey key = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
                if (key == null) {
                    throw new ConfigDecodeException("invalid namespaced key '" + raw + "'");
                }
                return key;
            },
            (key, ctx) -> ConfigNode.ScalarNode.of(key.asString())
    );

    public static final ConfigCodec<Component> COMPONENT = ConfigCodec.of(
            val -> {
                String text = val.asString("");
                return text == null || text.isEmpty() ? Component.empty() : MINI_MESSAGE.deserialize(text);
            },
            (comp, ctx) -> ConfigNode.ScalarNode.of(MINI_MESSAGE.serialize(comp))
    );

    public static final ConfigCodec<TextColor> TEXT_COLOR = ConfigCodec.of(
            val -> {
                String raw = val.asNonBlankString();
                if (raw == null) {
                    throw new ConfigDecodeException("missing text color");
                }
                TextColor hex = TextColor.fromHexString(raw.startsWith("#") ? raw : "#" + raw);
                if (hex != null) {
                    return hex;
                }
                var named = net.kyori.adventure.text.format.NamedTextColor.NAMES.value(raw.toLowerCase(Locale.ROOT));
                if (named != null) {
                    return named;
                }
                throw new ConfigDecodeException("invalid Adventure TextColor '" + raw + "'");
            },
            (color, ctx) -> ConfigNode.ScalarNode.of(color.asHexString())
    );

    public static final ConfigCodec<Color> BUKKIT_COLOR = ConfigCodec.of(
            PaperCodecs::decodeBukkitColor,
            (color, ctx) -> ConfigNode.ScalarNode.of(String.format("#%08X", color.asARGB()))
    );

    public static final ConfigCodec<Sound> SOUND = registryCodec(() -> Registry.SOUNDS, "sound");

    public static final ConfigCodec<PotionEffectType> POTION_EFFECT_TYPE = registryCodec(() -> Registry.MOB_EFFECT, "potion effect");

    private static <T extends org.bukkit.Keyed> ConfigCodec<T> registryCodec(
            Supplier<Registry<T>> registrySupplier,
            String label
    ) {
        return ConfigCodec.of(
                val -> {
                    String raw = val.asNonBlankString();
                    if (raw == null) {
                        throw new ConfigDecodeException("missing " + label);
                    }
                    if (Bukkit.getServer() == null) {
                        throw new ConfigDecodeException("Bukkit server not running; cannot resolve " + label + " '" + raw + "'");
                    }
                    Registry<T> registry = registrySupplier.get();
                    NamespacedKey exact = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
                    T matched = exact != null ? registry.get(exact) : null;
                    if (matched == null) {
                        throw new ConfigDecodeException("unknown Bukkit " + label + " '" + raw + "'");
                    }
                    return matched;
                },
                (value, ctx) -> ConfigNode.ScalarNode.of(registrySupplier.get().getKeyOrThrow(value).asString())
        );
    }

    public static CodecRegistry registerDefaults(CodecRegistry registry) {
        return registry
                .register(Material.class, MATERIAL)
                .register(Particle.class, PARTICLE)
                .register(NamespacedKey.class, NAMESPACED_KEY)
                .register(Component.class, COMPONENT)
                .register(TextColor.class, TEXT_COLOR)
                .register(Color.class, BUKKIT_COLOR)
                .register(Sound.class, SOUND)
                .register(PotionEffectType.class, POTION_EFFECT_TYPE);
    }

    private static Color decodeBukkitColor(ConfigValue val) {
        String raw = val.asNonBlankString();
        if (raw == null) {
            throw new ConfigDecodeException("missing color");
        }
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("transparent") || trimmed.equalsIgnoreCase("none")) {
            return Color.fromARGB(0);
        }
        if (trimmed.contains(",")) {
            String[] parts = trimmed.split(",");
            if (parts.length == 3 || parts.length == 4) {
                try {
                    if (parts.length == 3) {
                        return Color.fromRGB(
                                Integer.parseInt(parts[0].trim()),
                                Integer.parseInt(parts[1].trim()),
                                Integer.parseInt(parts[2].trim())
                        );
                    }
                    return Color.fromARGB(
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()),
                            Integer.parseInt(parts[3].trim())
                    );
                } catch (IllegalArgumentException ex) {
                    throw new ConfigDecodeException("invalid RGB/ARGB color '" + raw + "'", ex);
                }
            }
        }
        String hex = trimmed;
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        try {
            if (hex.length() == 6) {
                return Color.fromRGB(Integer.parseUnsignedInt(hex, 16));
            }
            if (hex.length() == 8) {
                return Color.fromARGB(Integer.parseUnsignedInt(hex, 16));
            }
        } catch (NumberFormatException ignored) {
        }
        throw new ConfigDecodeException("invalid color '" + raw + "' (expected #RRGGBB, #AARRGGBB, r,g,b, or 'transparent')");
    }
}
