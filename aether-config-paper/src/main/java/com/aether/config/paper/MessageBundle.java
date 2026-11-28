package com.aether.config.paper;

import com.aether.config.core.ConfigManager;
import com.aether.config.core.DirectoryConfig;
import com.aether.config.core.ReloadResult;
import com.aether.config.core.ResourceProvider;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.node.ConfigNode;
import com.aether.config.view.ConfigSection;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Multi-locale MiniMessage bundle backed by {@link DirectoryConfig} with fallback chain resolution
 * ({@code lang_COUNTRY -> lang -> defaultLocale}).
 */
public final class MessageBundle {

    private final DirectoryConfig<Map<String, MessageTemplate>> directory;
    private volatile Locale defaultLocale;

    private MessageBundle(DirectoryConfig<Map<String, MessageTemplate>> directory, Locale defaultLocale) {
        this.directory = directory;
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
    }

    public static Builder builder(ConfigManager manager, Path langDirectory) {
        return new Builder(manager, langDirectory);
    }

    public Locale defaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(Locale defaultLocale) {
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
    }

    public ConfigDiagnostics diagnostics() {
        return directory.diagnostics();
    }

    public ReloadResult<Map<String, Map<String, MessageTemplate>>> reload() {
        return directory.reload();
    }

    public CompletableFuture<ReloadResult<Map<String, Map<String, MessageTemplate>>>> reloadAsync(Executor executor) {
        return directory.reloadAsync(executor);
    }

    public MessageTemplate template(@Nullable Locale locale, String key) {
        Map<String, Map<String, MessageTemplate>> all = directory.all();
        for (String candidate : candidateLocaleKeys(locale != null ? locale : defaultLocale)) {
            Map<String, MessageTemplate> map = all.get(candidate);
            if (map != null) {
                MessageTemplate tpl = map.get(key);
                if (tpl != null) {
                    return tpl;
                }
            }
        }
        return MessageTemplate.of("<red>Missing message key: " + key);
    }

    public boolean hasKey(@Nullable Locale locale, String key) {
        Map<String, Map<String, MessageTemplate>> all = directory.all();
        for (String candidate : candidateLocaleKeys(locale != null ? locale : defaultLocale)) {
            Map<String, MessageTemplate> map = all.get(candidate);
            if (map != null && map.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    public Component render(@Nullable Locale locale, String key, TagResolver... resolvers) {
        return template(locale, key).render(resolvers);
    }

    public void send(Audience audience, @Nullable Locale locale, String key, TagResolver... resolvers) {
        MessageTemplate tpl = template(locale, key);
        if (!tpl.isEmpty()) {
            audience.sendMessage(tpl.render(resolvers));
        }
    }

    public void send(Player player, String key, TagResolver... resolvers) {
        send(player, player.locale(), key, resolvers);
    }

    private List<String> candidateLocaleKeys(Locale locale) {
        List<String> candidates = new ArrayList<>(4);
        String lang = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        if (!lang.isEmpty() && !country.isEmpty()) {
            candidates.add(lang + "_" + country);
        }
        if ("zh".equals(lang) && ("TW".equals(country) || "HK".equals(country) || "MO".equals(country))) {
            candidates.add("zh_CN");
        }
        if (!lang.isEmpty()) {
            candidates.add(lang);
            // Match any bundle sharing the language prefix (e.g. "en" -> "en_US")
            String prefix = lang + "_";
            for (String key : directory.all().keySet()) {
                if (key.startsWith(prefix) && !candidates.contains(key)) {
                    candidates.add(key);
                }
            }
        }
        String defLang = defaultLocale.getLanguage().toLowerCase(Locale.ROOT);
        String defCountry = defaultLocale.getCountry().toUpperCase(Locale.ROOT);
        if (!defLang.isEmpty() && !defCountry.isEmpty()) {
            String defFull = defLang + "_" + defCountry;
            if (!candidates.contains(defFull)) {
                candidates.add(defFull);
            }
        }
        if (!defLang.isEmpty() && !candidates.contains(defLang)) {
            candidates.add(defLang);
        }
        return candidates;
    }

    private static Map<String, MessageTemplate> flattenSection(ConfigSection root) {
        Map<String, MessageTemplate> out = new LinkedHashMap<>();
        collect("", root.node(), out);
        return Collections.unmodifiableMap(out);
    }

    private static void collect(String prefix, ConfigNode.MapNode node, Map<String, MessageTemplate> out) {
        for (ConfigNode.MapEntry entry : node.entries().values()) {
            String path = prefix.isEmpty() ? entry.key() : prefix + "." + entry.key();
            ConfigNode val = entry.value();
            if (val instanceof ConfigNode.MapNode nested) {
                collect(path, nested, out);
            } else if (val instanceof ConfigNode.ListNode listNode) {
                StringBuilder joined = new StringBuilder();
                List<ConfigNode> elems = listNode.elements();
                for (int i = 0; i < elems.size(); i++) {
                    if (i > 0) {
                        joined.append('\n');
                    }
                    Object raw = elems.get(i).rawValue();
                    joined.append(raw != null ? String.valueOf(raw) : "");
                }
                out.put(path, MessageTemplate.of(joined.toString()));
            } else if (!(val instanceof ConfigNode.NullNode)) {
                Object raw = val.rawValue();
                out.put(path, MessageTemplate.of(raw != null ? String.valueOf(raw) : ""));
            }
        }
    }

    public static final class Builder {
        private final ConfigManager manager;
        private final Path directory;
        private Locale defaultLocale = Locale.US;
        private @Nullable ResourceProvider resourceProvider;
        private DirectoryConfig.DefaultSeedPolicy seedPolicy = DirectoryConfig.DefaultSeedPolicy.FILL_MISSING;
        private String[] defaultResources = new String[0];

        private Builder(ConfigManager manager, Path directory) {
            this.manager = Objects.requireNonNull(manager, "manager");
            this.directory = Objects.requireNonNull(directory, "directory");
        }

        public Builder defaultLocale(Locale defaultLocale) {
            this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
            return this;
        }

        public Builder defaults(ResourceProvider provider, String... resourcePaths) {
            return defaults(provider, DirectoryConfig.DefaultSeedPolicy.FILL_MISSING, resourcePaths);
        }

        public Builder defaults(
                ResourceProvider provider,
                DirectoryConfig.DefaultSeedPolicy seedPolicy,
                String... resourcePaths
        ) {
            this.resourceProvider = provider;
            this.seedPolicy = seedPolicy;
            this.defaultResources = resourcePaths != null ? resourcePaths : new String[0];
            return this;
        }

        public MessageBundle load() {
            DirectoryConfig.Builder<Map<String, MessageTemplate>> dirBuilder = manager.directory(directory)
                    .factory((id, section) -> flattenSection(section))
                    .fileAsEntry(false);
            if (resourceProvider != null && defaultResources.length > 0) {
                dirBuilder.defaults(resourceProvider, seedPolicy, defaultResources);
            }
            return new MessageBundle(dirBuilder.load(), defaultLocale);
        }
    }
}
