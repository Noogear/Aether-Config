package com.aether.config.interpolate;

import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.SourceSpan;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves {@code ${...}} placeholders (with optional {@code :fallback}) inside {@link ConfigNode} trees
 * without altering single-brace runtime placeholders such as {@code {player}}.
 */
public final class NodeInterpolator {

    private final InterpolationMode mode;
    private final Function<String, @Nullable String> envLookup;
    private final Function<String, @Nullable String> sysLookup;

    public NodeInterpolator(
            InterpolationMode mode,
            Function<String, @Nullable String> envLookup,
            Function<String, @Nullable String> sysLookup
    ) {
        this.mode = mode != null ? mode : InterpolationMode.DISABLED;
        this.envLookup = envLookup;
        this.sysLookup = sysLookup;
    }

    public ConfigNode.MapNode interpolate(
            ConfigNode.MapNode root,
            ConfigDiagnostics diagnostics
    ) {
        if (mode == InterpolationMode.DISABLED || root.isEmpty()) {
            return root;
        }
        return (ConfigNode.MapNode) transform(root, root, "", new HashSet<>(), diagnostics);
    }

    private ConfigNode transform(
            ConfigNode node,
            ConfigNode.MapNode root,
            String path,
            Set<String> visiting,
            ConfigDiagnostics diagnostics
    ) {
        return switch (node) {
            case ConfigNode.NullNode n -> n;
            case ConfigNode.ScalarNode s -> {
                if (!(s.value() instanceof String text) || !text.contains("${")) {
                    yield s;
                }
                String resolved = resolveString(text, root, path, s.span(), visiting, diagnostics);
                yield resolved.equals(text) ? s : s.withValue(resolved);
            }
            case ConfigNode.ListNode list -> {
                List<ConfigNode> elems = new ArrayList<>(list.elements().size());
                boolean changed = false;
                for (int i = 0; i < list.elements().size(); i++) {
                    ConfigNode orig = list.elements().get(i);
                    ConfigNode next = transform(orig, root, path + "[" + i + "]", visiting, diagnostics);
                    if (next != orig) {
                        changed = true;
                    }
                    elems.add(next);
                }
                yield changed ? new ConfigNode.ListNode(elems, list.span(), list.comments()) : list;
            }
            case ConfigNode.MapNode map -> {
                Map<String, ConfigNode.MapEntry> out = new LinkedHashMap<>(map.entries().size());
                boolean changed = false;
                for (ConfigNode.MapEntry entry : map.entries().values()) {
                    String childPath = path.isEmpty() ? entry.key() : path + "." + entry.key();
                    ConfigNode nextVal = transform(entry.value(), root, childPath, visiting, diagnostics);
                    if (nextVal != entry.value()) {
                        changed = true;
                        out.put(entry.key(), entry.withValue(nextVal));
                    } else {
                        out.put(entry.key(), entry);
                    }
                }
                yield changed ? new ConfigNode.MapNode(out, map.span(), map.comments()) : map;
            }
        };
    }

    private String resolveString(
            String input,
            ConfigNode.MapNode root,
            String currentPath,
            SourceSpan span,
            Set<String> visiting,
            ConfigDiagnostics diagnostics
    ) {
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\\' && i + 2 < input.length() && input.charAt(i + 1) == '$' && input.charAt(i + 2) == '{') {
                out.append("${");
                i += 3;
                continue;
            }
            if (c == '$' && i + 1 < input.length() && input.charAt(i + 1) == '{') {
                int end = findClosingBrace(input, i + 2);
                if (end > 0) {
                    String expr = input.substring(i + 2, end);
                    String value = evaluateExpr(expr, root, currentPath, span, visiting, diagnostics);
                    if (value != null) {
                        out.append(value);
                    } else {
                        out.append("${").append(expr).append('}');
                    }
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int findClosingBrace(String s, int start) {
        int depth = 1;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private @Nullable String evaluateExpr(
            String expr,
            ConfigNode.MapNode root,
            String currentPath,
            SourceSpan span,
            Set<String> visiting,
            ConfigDiagnostics diagnostics
    ) {
        String keyPart = expr;
        @Nullable String fallback = null;
        int colonIdx = findFallbackColon(expr);
        if (colonIdx >= 0) {
            keyPart = expr.substring(0, colonIdx);
            fallback = resolveString(expr.substring(colonIdx + 1), root, currentPath, span, visiting, diagnostics);
        }

        String resolved = null;
        if (keyPart.startsWith("env:")) {
            resolved = envLookup.apply(keyPart.substring(4));
        } else if (keyPart.startsWith("sys:")) {
            resolved = sysLookup.apply(keyPart.substring(4));
        } else if (keyPart.startsWith("ref:")) {
            if (mode == InterpolationMode.ALL) {
                resolved = resolveReference(keyPart.substring(4), root, span, visiting, diagnostics);
            }
        } else {
            if (mode == InterpolationMode.ALL) {
                resolved = resolveReference(keyPart, root, span, visiting, diagnostics);
            }
            if (resolved == null) {
                resolved = envLookup.apply(keyPart);
            }
            if (resolved == null) {
                resolved = sysLookup.apply(keyPart);
            }
        }

        if (resolved != null) {
            return resolved;
        }
        if (fallback != null) {
            return fallback;
        }
        diagnostics.warn(DiagnosticCategory.INTERPOLATION, span, currentPath,
                "Unresolved placeholder '${" + expr + "}'.");
        return null;
    }

    private static int findFallbackColon(String expr) {
        int start = 0;
        if (expr.startsWith("env:") || expr.startsWith("sys:") || expr.startsWith("ref:")) {
            start = 4;
        }
        int depth = 0;
        for (int i = start; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '$' && i + 1 < expr.length() && expr.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private @Nullable String resolveReference(
            String refPath,
            ConfigNode.MapNode root,
            SourceSpan originSpan,
            Set<String> visiting,
            ConfigDiagnostics diagnostics
    ) {
        if (!visiting.add(refPath)) {
            diagnostics.error(DiagnosticCategory.INTERPOLATION, originSpan, refPath,
                    "Circular placeholder reference detected for '" + refPath + "'.");
            return null;
        }
        try {
            ConfigNode target = lookupPath(root, refPath);
            if (target == null || target instanceof ConfigNode.NullNode) {
                return null;
            }
            if (target instanceof ConfigNode.ScalarNode s) {
                String raw = String.valueOf(s.value());
                if (raw.contains("${")) {
                    return resolveString(raw, root, refPath, s.span(), visiting, diagnostics);
                }
                return raw;
            }
            return null;
        } finally {
            visiting.remove(refPath);
        }
    }

    private static @Nullable ConfigNode lookupPath(ConfigNode.MapNode root, String path) {
        ConfigNode direct = root.child(path);
        if (direct != null) {
            return direct;
        }
        ConfigNode cur = root;
        for (String seg : ConfigDocument.splitPath(path)) {
            if (!(cur instanceof ConfigNode.MapNode map)) {
                return null;
            }
            cur = map.child(seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }
}
