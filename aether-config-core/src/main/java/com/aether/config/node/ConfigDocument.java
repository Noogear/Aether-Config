package com.aether.config.node;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable comment-preserving AST document used for migration, incremental patching, and default generation.
 */
public final class ConfigDocument {

    private final Map<String, MutableEntry> rootEntries = new LinkedHashMap<>();
    private final SourceSpan rootSpan;
    private NodeComments rootComments;
    private boolean modified = false;

    private ConfigDocument(SourceSpan rootSpan, NodeComments rootComments) {
        this.rootSpan = rootSpan != null ? rootSpan : SourceSpan.UNKNOWN;
        this.rootComments = rootComments != null ? rootComments : NodeComments.EMPTY;
    }

    public static ConfigDocument empty(String source) {
        return new ConfigDocument(SourceSpan.ofSource(source), NodeComments.EMPTY);
    }

    public static ConfigDocument fromNode(ConfigNode.MapNode root) {
        ConfigDocument doc = new ConfigDocument(root.span(), root.comments());
        copyEntries(root, doc.rootEntries);
        return doc;
    }

    private static void copyEntries(ConfigNode.MapNode src, Map<String, MutableEntry> dest) {
        for (ConfigNode.MapEntry entry : src.entries().values()) {
            MutableEntry mutable = new MutableEntry(entry.key(), entry.value(), entry.keyComments(), entry.keySpan());
            if (entry.value() instanceof ConfigNode.MapNode nested) {
                mutable.children = new LinkedHashMap<>();
                copyEntries(nested, mutable.children);
            }
            dest.put(entry.key(), mutable);
        }
    }

    public boolean isModified() {
        return modified;
    }

    public NodeComments headerComments() {
        return rootComments;
    }

    public void setHeader(List<String> headerLines) {
        NodeComments updated = rootComments.withBlock(headerLines);
        if (!updated.equals(rootComments)) {
            rootComments = updated;
            modified = true;
        }
    }

    public boolean has(String dotPath) {
        return getEntry(dotPath) != null;
    }

    public @Nullable ConfigNode get(String dotPath) {
        MutableEntry entry = getEntry(dotPath);
        return entry != null ? entry.toValueNode() : null;
    }

    public @Nullable Object getRaw(String dotPath) {
        ConfigNode node = get(dotPath);
        return node != null ? node.rawValue() : null;
    }

    public @Nullable String getString(String dotPath) {
        Object raw = getRaw(dotPath);
        return raw != null ? String.valueOf(raw) : null;
    }

    public int getInt(String dotPath, int defaultValue) {
        Object raw = getRaw(dotPath);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public void set(String dotPath, ConfigNode value) {
        List<String> segments = splitPath(dotPath);
        Map<String, MutableEntry> parent = navigateParent(segments, true);
        if (parent == null) {
            return;
        }
        String leafKey = segments.getLast();
        MutableEntry existing = parent.get(leafKey);
        NodeComments preservedComments = existing != null ? existing.keyComments : NodeComments.EMPTY;
        SourceSpan preservedSpan = existing != null ? existing.keySpan : SourceSpan.UNKNOWN;

        MutableEntry replacement = new MutableEntry(leafKey, value, preservedComments, preservedSpan);
        if (value instanceof ConfigNode.MapNode nestedMap) {
            replacement.children = new LinkedHashMap<>();
            copyEntries(nestedMap, replacement.children);
        }
        parent.put(leafKey, replacement);
        modified = true;
    }

    public void setRaw(String dotPath, @Nullable Object rawValue) {
        set(dotPath, toNode(rawValue));
    }

    public void setBlockComments(String dotPath, List<String> comments) {
        updateBlockComments(dotPath, comments, false);
    }

    public void setBlockCommentsIfAbsent(String dotPath, List<String> comments) {
        updateBlockComments(dotPath, comments, true);
    }

    public void setInlineComments(String dotPath, List<String> comments) {
        updateInlineComments(dotPath, comments, false);
    }

    public void setInlineCommentsIfAbsent(String dotPath, List<String> comments) {
        updateInlineComments(dotPath, comments, true);
    }

    private void updateBlockComments(String dotPath, List<String> comments, boolean onlyIfAbsent) {
        MutableEntry entry = getEntry(dotPath);
        if (entry == null || (onlyIfAbsent && !entry.keyComments.block().isEmpty())) {
            return;
        }
        NodeComments updated = entry.keyComments.withBlock(comments);
        if (!updated.equals(entry.keyComments)) {
            entry.keyComments = updated;
            modified = true;
        }
    }

    private void updateInlineComments(String dotPath, List<String> comments, boolean onlyIfAbsent) {
        MutableEntry entry = getEntry(dotPath);
        if (entry == null) {
            return;
        }
        if (entry.children != null) {
            if (onlyIfAbsent && !entry.keyComments.inline().isEmpty()) {
                return;
            }
            NodeComments updated = entry.keyComments.withInline(comments);
            if (!updated.equals(entry.keyComments)) {
                entry.keyComments = updated;
                modified = true;
            }
        } else {
            if (onlyIfAbsent && (!entry.keyComments.inline().isEmpty() || !entry.leafValue.comments().inline().isEmpty())) {
                return;
            }
            NodeComments updated = entry.leafValue.comments().withInline(comments);
            if (!updated.equals(entry.leafValue.comments())) {
                entry.leafValue = entry.leafValue.withComments(updated);
                modified = true;
            }
        }
    }

    public boolean remove(String dotPath) {
        List<String> segments = splitPath(dotPath);
        Map<String, MutableEntry> parent = navigateParent(segments, false);
        if (parent != null && parent.remove(segments.getLast()) != null) {
            modified = true;
            return true;
        }
        return false;
    }

    public boolean rename(String oldPath, String newPath) {
        MutableEntry oldEntry = getEntry(oldPath);
        if (oldEntry == null) {
            return false;
        }
        ConfigNode existing = oldEntry.toValueNode();
        NodeComments oldComments = oldEntry.keyComments;
        remove(oldPath);
        set(newPath, existing);
        if (!oldComments.isEmpty()) {
            setBlockCommentsIfAbsent(newPath, oldComments.block());
            setInlineCommentsIfAbsent(newPath, oldComments.inline());
        }
        return true;
    }

    public boolean copy(String fromPath, String toPath) {
        ConfigNode existing = get(fromPath);
        if (existing == null) {
            return false;
        }
        set(toPath, existing);
        return true;
    }

    public ConfigNode.MapNode toNode() {
        return new ConfigNode.MapNode(freezeEntries(rootEntries), rootSpan, rootComments);
    }

    private static Map<String, ConfigNode.MapEntry> freezeEntries(Map<String, MutableEntry> entries) {
        Map<String, ConfigNode.MapEntry> frozen = new LinkedHashMap<>(entries.size());
        for (MutableEntry entry : entries.values()) {
            frozen.put(entry.key, new ConfigNode.MapEntry(
                    entry.key,
                    entry.toValueNode(),
                    entry.keyComments,
                    entry.keySpan
            ));
        }
        return frozen;
    }

    private @Nullable MutableEntry getEntry(String dotPath) {
        List<String> segments = splitPath(dotPath);
        Map<String, MutableEntry> parent = navigateParent(segments, false);
        return parent != null ? parent.get(segments.getLast()) : null;
    }

    private @Nullable Map<String, MutableEntry> navigateParent(List<String> segments, boolean createMissing) {
        if (segments.isEmpty()) {
            return null;
        }
        Map<String, MutableEntry> current = rootEntries;
        for (int i = 0; i < segments.size() - 1; i++) {
            String seg = segments.get(i);
            MutableEntry next = current.get(seg);
            if (next == null || next.children == null) {
                if (!createMissing) {
                    return null;
                }
                next = new MutableEntry(
                        seg,
                        ConfigNode.MapNode.EMPTY,
                        next != null ? next.keyComments : NodeComments.EMPTY,
                        next != null ? next.keySpan : SourceSpan.UNKNOWN
                );
                next.children = new LinkedHashMap<>();
                current.put(seg, next);
            }
            current = next.children;
        }
        return current;
    }

    public static ConfigNode toNode(@Nullable Object raw) {
        if (raw == null) {
            return ConfigNode.NullNode.INSTANCE;
        }
        if (raw instanceof ConfigNode node) {
            return node;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, ConfigNode.MapEntry> entries = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null) {
                    String k = String.valueOf(e.getKey());
                    entries.put(k, ConfigNode.MapEntry.of(k, toNode(e.getValue())));
                }
            }
            return new ConfigNode.MapNode(entries, SourceSpan.UNKNOWN, NodeComments.EMPTY);
        }
        if (raw instanceof Collection<?> col) {
            List<ConfigNode> list = new ArrayList<>(col.size());
            for (Object item : col) {
                list.add(toNode(item));
            }
            return ConfigNode.ListNode.of(list);
        }
        return ConfigNode.ScalarNode.of(raw);
    }

    public static List<String> splitPath(String path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' && i + 1 < path.length() && path.charAt(i + 1) == '.') {
                current.append('.');
                i++;
            } else if (c == '.') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static final class MutableEntry {
        final String key;
        ConfigNode leafValue;
        @Nullable Map<String, MutableEntry> children;
        NodeComments keyComments;
        final SourceSpan keySpan;
        final NodeComments mapComments;
        final SourceSpan mapSpan;

        MutableEntry(String key, ConfigNode value, NodeComments keyComments, SourceSpan keySpan) {
            this.key = key;
            this.leafValue = value;
            this.keyComments = keyComments != null ? keyComments : NodeComments.EMPTY;
            this.keySpan = keySpan != null ? keySpan : SourceSpan.UNKNOWN;
            if (value instanceof ConfigNode.MapNode map) {
                this.mapComments = map.comments();
                this.mapSpan = map.span();
            } else {
                this.mapComments = NodeComments.EMPTY;
                this.mapSpan = SourceSpan.UNKNOWN;
            }
        }

        ConfigNode toValueNode() {
            return children != null
                    ? new ConfigNode.MapNode(freezeEntries(children), mapSpan, mapComments)
                    : leafValue;
        }
    }
}
