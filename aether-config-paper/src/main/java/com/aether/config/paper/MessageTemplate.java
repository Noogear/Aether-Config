package com.aether.config.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Objects;

/**
 * Immutable MiniMessage template preserving raw markup so runtime {@link TagResolver} placeholders
 * (such as {@code <player>} or {@code <island>}) can be bound dynamically when rendering.
 */
public record MessageTemplate(String raw) {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static final MessageTemplate EMPTY = new MessageTemplate("");

    public MessageTemplate {
        raw = raw != null ? raw : "";
    }

    public static MessageTemplate of(String raw) {
        return raw == null || raw.isEmpty() ? EMPTY : new MessageTemplate(raw);
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }

    public Component render() {
        return raw.isEmpty() ? Component.empty() : MINI_MESSAGE.deserialize(raw);
    }

    public Component render(TagResolver... resolvers) {
        if (raw.isEmpty()) {
            return Component.empty();
        }
        return resolvers == null || resolvers.length == 0
                ? MINI_MESSAGE.deserialize(raw)
                : MINI_MESSAGE.deserialize(raw, resolvers);
    }

    public Component render(Iterable<? extends TagResolver> resolvers) {
        if (raw.isEmpty()) {
            return Component.empty();
        }
        Objects.requireNonNull(resolvers, "resolvers");
        return MINI_MESSAGE.deserialize(raw, TagResolver.resolver(resolvers));
    }
}
