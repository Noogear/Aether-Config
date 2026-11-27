package com.aether.config.node;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable format-agnostic AST representing parsed configuration data, source positions, and comments.
 */
public sealed interface ConfigNode permits
        ConfigNode.ScalarNode,
        ConfigNode.MapNode,
        ConfigNode.ListNode,
        ConfigNode.NullNode {

    SourceSpan span();

    NodeComments comments();

    @Nullable Object rawValue();

    ConfigNode withComments(NodeComments comments);

    ConfigNode withSpan(SourceSpan span);

    record ScalarNode(
            Object value,
            ScalarStyle style,
            SourceSpan span,
            NodeComments comments
    ) implements ConfigNode {

        public ScalarNode {
            if (value == null) {
                throw new IllegalArgumentException("ScalarNode value must not be null; use NullNode instead");
            }
            style = style != null ? style : ScalarStyle.PLAIN;
            span = span != null ? span : SourceSpan.UNKNOWN;
            comments = comments != null ? comments : NodeComments.EMPTY;
        }

        public static ScalarNode of(Object value) {
            return new ScalarNode(value, ScalarStyle.PLAIN, SourceSpan.UNKNOWN, NodeComments.EMPTY);
        }

        @Override
        public Object rawValue() {
            return value;
        }

        @Override
        public ScalarNode withComments(NodeComments comments) {
            return new ScalarNode(value, style, span, comments);
        }

        @Override
        public ScalarNode withSpan(SourceSpan span) {
            return new ScalarNode(value, style, span, comments);
        }

        public ScalarNode withValue(Object newValue) {
            return new ScalarNode(newValue, style, span, comments);
        }
    }

    record NullNode(
            SourceSpan span,
            NodeComments comments
    ) implements ConfigNode {

        public static final NullNode INSTANCE = new NullNode(SourceSpan.UNKNOWN, NodeComments.EMPTY);

        public NullNode {
            span = span != null ? span : SourceSpan.UNKNOWN;
            comments = comments != null ? comments : NodeComments.EMPTY;
        }

        @Override
        public @Nullable Object rawValue() {
            return null;
        }

        @Override
        public NullNode withComments(NodeComments comments) {
            return new NullNode(span, comments);
        }

        @Override
        public NullNode withSpan(SourceSpan span) {
            return new NullNode(span, comments);
        }
    }

    record ListNode(
            List<ConfigNode> elements,
            SourceSpan span,
            NodeComments comments
    ) implements ConfigNode {

        public static final ListNode EMPTY = new ListNode(List.of(), SourceSpan.UNKNOWN, NodeComments.EMPTY);

        public ListNode {
            elements = elements == null || elements.isEmpty() ? List.of() : List.copyOf(elements);
            span = span != null ? span : SourceSpan.UNKNOWN;
            comments = comments != null ? comments : NodeComments.EMPTY;
        }

        public static ListNode of(List<ConfigNode> elements) {
            return new ListNode(elements, SourceSpan.UNKNOWN, NodeComments.EMPTY);
        }

        @Override
        public List<Object> rawValue() {
            List<Object> list = new ArrayList<>(elements.size());
            for (ConfigNode element : elements) {
                list.add(element.rawValue());
            }
            return Collections.unmodifiableList(list);
        }

        @Override
        public ListNode withComments(NodeComments comments) {
            return new ListNode(elements, span, comments);
        }

        @Override
        public ListNode withSpan(SourceSpan span) {
            return new ListNode(elements, span, comments);
        }
    }

    record MapEntry(
            String key,
            ConfigNode value,
            NodeComments keyComments,
            SourceSpan keySpan
    ) {
        public MapEntry {
            if (key == null) {
                throw new IllegalArgumentException("MapEntry key must not be null");
            }
            value = value != null ? value : NullNode.INSTANCE;
            keyComments = keyComments != null ? keyComments : NodeComments.EMPTY;
            keySpan = keySpan != null ? keySpan : SourceSpan.UNKNOWN;
        }

        public static MapEntry of(String key, ConfigNode value) {
            return new MapEntry(key, value, NodeComments.EMPTY, SourceSpan.UNKNOWN);
        }

        public MapEntry withValue(ConfigNode newValue) {
            return new MapEntry(key, newValue, keyComments, keySpan);
        }
    }

    record MapNode(
            Map<String, MapEntry> entries,
            SourceSpan span,
            NodeComments comments
    ) implements ConfigNode {

        public static final MapNode EMPTY = new MapNode(Map.of(), SourceSpan.UNKNOWN, NodeComments.EMPTY);

        public MapNode {
            if (entries == null || entries.isEmpty()) {
                entries = Collections.emptyMap();
            } else {
                entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            }
            span = span != null ? span : SourceSpan.UNKNOWN;
            comments = comments != null ? comments : NodeComments.EMPTY;
        }

        public static MapNode ofEntries(List<MapEntry> entryList) {
            return ofEntries(entryList, SourceSpan.UNKNOWN, NodeComments.EMPTY);
        }

        public static MapNode ofEntries(List<MapEntry> entryList, SourceSpan span, NodeComments comments) {
            Map<String, MapEntry> map = new LinkedHashMap<>(entryList.size());
            for (MapEntry entry : entryList) {
                map.put(entry.key(), entry);
            }
            return new MapNode(map, span, comments);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public @Nullable MapEntry entry(String key) {
            return entries.get(key);
        }

        public @Nullable ConfigNode child(String key) {
            MapEntry e = entries.get(key);
            return e != null ? e.value() : null;
        }

        @Override
        public Map<String, Object> rawValue() {
            Map<String, Object> raw = new LinkedHashMap<>(entries.size());
            for (Map.Entry<String, MapEntry> e : entries.entrySet()) {
                raw.put(e.getKey(), e.getValue().value().rawValue());
            }
            return Collections.unmodifiableMap(raw);
        }

        @Override
        public MapNode withComments(NodeComments comments) {
            return new MapNode(entries, span, comments);
        }

        @Override
        public MapNode withSpan(SourceSpan span) {
            return new MapNode(entries, span, comments);
        }
    }
}
