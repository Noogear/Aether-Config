package com.aether.config.yaml;

import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.format.ConfigFormat;
import com.aether.config.node.ConfigNode;
import com.aether.config.node.NodeComments;
import com.aether.config.node.ScalarStyle;
import com.aether.config.node.SourceSpan;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SnakeYAML 2.x AST-backed {@link ConfigFormat} preserving 1-based line/column spans,
 * source snippet lines, block/inline/header comments, and scalar quoting styles.
 */
public final class YamlFormat implements ConfigFormat {

    private static final Set<String> EXTENSIONS = Set.of("yml", "yaml");
    private static final Pattern NUMBER_LIKE = Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$");

    private final int indent;
    private final int width;
    private final boolean allowDuplicateKeys;

    private YamlFormat(int indent, int width, boolean allowDuplicateKeys) {
        this.indent = indent;
        this.width = width;
        this.allowDuplicateKeys = allowDuplicateKeys;
    }

    public static YamlFormat create() {
        return new YamlFormat(2, 250, false);
    }

    public static YamlFormat of(int indent, int width, boolean allowDuplicateKeys) {
        return new YamlFormat(Math.max(2, indent), Math.max(40, width), allowDuplicateKeys);
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public ConfigNode.MapNode parse(String sourceName, Reader reader, ConfigDiagnostics diagnostics) {
        String content;
        try {
            StringWriter sw = new StringWriter();
            reader.transferTo(sw);
            content = sw.toString();
        } catch (IOException ex) {
            diagnostics.error(DiagnosticCategory.IO, SourceSpan.ofSource(sourceName), "",
                    "Failed to read YAML stream: " + ex.getMessage());
            return ConfigNode.MapNode.EMPTY;
        }

        if (content.isBlank()) {
            return new ConfigNode.MapNode(Map.of(), SourceSpan.ofSource(sourceName), NodeComments.EMPTY);
        }

        String[] lines = content.split("\r?\n", -1);
        LoaderOptions loaderOptions = createLoaderOptions();
        Yaml yaml = createYaml(loaderOptions, createDumperOptions());
        ScalarExtractor extractor = new ScalarExtractor(loaderOptions);

        Node root;
        try {
            root = yaml.compose(new StringReader(content));
        } catch (MarkedYAMLException ex) {
            SourceSpan span = spanFromMark(sourceName, ex.getProblemMark(), null, lines);
            String msg = ex.getProblem() != null ? ex.getProblem() : ex.getMessage();
            diagnostics.error(DiagnosticCategory.SYNTAX, span, "", "YAML syntax error: " + msg);
            return ConfigNode.MapNode.EMPTY;
        } catch (YAMLException ex) {
            diagnostics.error(DiagnosticCategory.SYNTAX, SourceSpan.ofSource(sourceName), "",
                    "Failed to parse YAML: " + ex.getMessage());
            return ConfigNode.MapNode.EMPTY;
        }

        if (root == null) {
            return new ConfigNode.MapNode(Map.of(), SourceSpan.ofSource(sourceName), NodeComments.EMPTY);
        }
        if (!(root instanceof MappingNode mapping)) {
            diagnostics.error(DiagnosticCategory.SYNTAX, spanOf(sourceName, root, lines), "",
                    "YAML root must be a key-value mapping.");
            return ConfigNode.MapNode.EMPTY;
        }

        return toMapNode(sourceName, mapping, "", lines, extractor, diagnostics);
    }

    @Override
    public void write(ConfigNode.MapNode root, Writer writer) throws IOException {
        Yaml yaml = createYaml(createLoaderOptions(), createDumperOptions());
        yaml.serialize(fromMapNode(root), writer);
    }

    private LoaderOptions createLoaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(allowDuplicateKeys);
        options.setProcessComments(true);
        return options;
    }

    private static Yaml createYaml(LoaderOptions loaderOptions, DumperOptions dumperOptions) {
        return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);
    }

    private DumperOptions createDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setProcessComments(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(indent);
        options.setWidth(width);
        options.setSplitLines(false);
        options.setAllowUnicode(true);
        return options;
    }

    private ConfigNode.MapNode toMapNode(
            String sourceName,
            MappingNode node,
            String basePath,
            String[] lines,
            ScalarExtractor extractor,
            ConfigDiagnostics diagnostics
    ) {
        if (node.isMerged()) {
            try {
                extractor.flatten(node);
            } catch (MarkedYAMLException ex) {
                SourceSpan span = spanFromMark(sourceName, ex.getProblemMark(), null, lines);
                diagnostics.error(DiagnosticCategory.SYNTAX, span, basePath, "YAML merge error: " + ex.getProblem());
            }
        }

        Map<String, ConfigNode.MapEntry> entries = new LinkedHashMap<>();
        for (NodeTuple tuple : node.getValue()) {
            Node keyNode = tuple.getKeyNode();
            String key;
            if (keyNode instanceof ScalarNode scKey) {
                key = scKey.getValue() != null ? scKey.getValue() : "null";
            } else {
                Object keyRaw = extractor.extract(keyNode);
                key = keyRaw != null ? String.valueOf(keyRaw) : "null";
            }
            SourceSpan keySpan = spanOf(sourceName, keyNode, lines);
            NodeComments keyComments = commentsOf(keyNode);
            String childPath = basePath.isEmpty() ? key : basePath + "." + key;

            if (!allowDuplicateKeys && entries.containsKey(key)) {
                diagnostics.error(DiagnosticCategory.SYNTAX, keySpan, childPath,
                        "Duplicate configuration key '" + key + "'.");
                continue;
            }

            ConfigNode valNode = toConfigNode(sourceName, tuple.getValueNode(), childPath, lines, extractor, diagnostics);
            entries.put(key, new ConfigNode.MapEntry(key, valNode, keyComments, keySpan));
        }

        return new ConfigNode.MapNode(entries, spanOf(sourceName, node, lines), commentsOf(node));
    }

    private ConfigNode toConfigNode(
            String sourceName,
            Node node,
            String path,
            String[] lines,
            ScalarExtractor extractor,
            ConfigDiagnostics diagnostics
    ) {
        if (node instanceof MappingNode mn) {
            return toMapNode(sourceName, mn, path, lines, extractor, diagnostics);
        }
        if (node instanceof SequenceNode sn) {
            List<ConfigNode> elems = new ArrayList<>(sn.getValue().size());
            List<Node> rawList = sn.getValue();
            for (int i = 0; i < rawList.size(); i++) {
                elems.add(toConfigNode(sourceName, rawList.get(i), path + "[" + i + "]", lines, extractor, diagnostics));
            }
            return new ConfigNode.ListNode(elems, spanOf(sourceName, sn, lines), commentsOf(sn));
        }
        if (node instanceof ScalarNode sc) {
            SourceSpan span = spanOf(sourceName, sc, lines);
            NodeComments comments = commentsOf(sc);
            Object val;
            try {
                val = extractor.extract(sc);
            } catch (Exception ex) {
                val = sc.getValue();
            }
            if (val == null || Tag.NULL.equals(sc.getTag())) {
                return new ConfigNode.NullNode(span, comments);
            }
            return new ConfigNode.ScalarNode(val, mapStyle(sc.getScalarStyle()), span, comments);
        }
        return new ConfigNode.NullNode(spanOf(sourceName, node, lines), commentsOf(node));
    }

    private static MappingNode fromMapNode(ConfigNode.MapNode mapNode) {
        List<NodeTuple> tuples = new ArrayList<>(mapNode.entries().size());
        for (ConfigNode.MapEntry entry : mapNode.entries().values()) {
            ScalarNode keyNode = new ScalarNode(
                    Tag.STR,
                    entry.key(),
                    null,
                    null,
                    needsQuoting(entry.key()) ? DumperOptions.ScalarStyle.SINGLE_QUOTED : DumperOptions.ScalarStyle.PLAIN
            );
            applyComments(keyNode, entry.keyComments());

            Node valNode = toSnakeNode(entry.value());
            tuples.add(new NodeTuple(keyNode, valNode));
        }
        MappingNode root = new MappingNode(Tag.MAP, tuples, DumperOptions.FlowStyle.BLOCK);
        applyComments(root, mapNode.comments());
        return root;
    }

    private static Node toSnakeNode(ConfigNode node) {
        return switch (node) {
            case ConfigNode.NullNode n -> {
                ScalarNode sn = new ScalarNode(Tag.NULL, "null", null, null, DumperOptions.ScalarStyle.PLAIN);
                applyComments(sn, n.comments());
                yield sn;
            }
            case ConfigNode.ScalarNode s -> {
                ScalarNode sn = scalarToSnake(s);
                applyComments(sn, s.comments());
                yield sn;
            }
            case ConfigNode.ListNode l -> {
                List<Node> elems = new ArrayList<>(l.elements().size());
                for (ConfigNode child : l.elements()) {
                    elems.add(toSnakeNode(child));
                }
                SequenceNode seq = new SequenceNode(Tag.SEQ, elems, DumperOptions.FlowStyle.BLOCK);
                applyComments(seq, l.comments());
                yield seq;
            }
            case ConfigNode.MapNode m -> fromMapNode(m);
        };
    }

    private static ScalarNode scalarToSnake(ConfigNode.ScalarNode scalar) {
        Object val = scalar.value();
        DumperOptions.ScalarStyle style = toSnakeStyle(scalar.style());

        if (val instanceof Boolean b) {
            return new ScalarNode(Tag.BOOL, b.toString(), null, null, DumperOptions.ScalarStyle.PLAIN);
        }
        if (val instanceof Integer || val instanceof Long || val instanceof Short || val instanceof Byte || val instanceof BigInteger) {
            return new ScalarNode(Tag.INT, val.toString(), null, null, style);
        }
        if (val instanceof Double || val instanceof Float || val instanceof BigDecimal) {
            return new ScalarNode(Tag.FLOAT, val.toString(), null, null, style);
        }
        String text = String.valueOf(val);
        if (text.contains("\n")) {
            style = DumperOptions.ScalarStyle.LITERAL;
        } else if (style == DumperOptions.ScalarStyle.PLAIN && needsQuoting(text)) {
            style = DumperOptions.ScalarStyle.SINGLE_QUOTED;
        }
        return new ScalarNode(Tag.STR, text, null, null, style);
    }

    private static boolean needsQuoting(String s) {
        if (s.isEmpty()) {
            return true;
        }
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)
                || "null".equalsIgnoreCase(s) || "~".equals(s)
                || "yes".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s)) {
            return true;
        }
        if (NUMBER_LIKE.matcher(s).matches()) {
            return true;
        }
        char first = s.charAt(0);
        if (first == ' ' || first == '\t' || s.charAt(s.length() - 1) == ' ') {
            return true;
        }
        return switch (first) {
            case '#', '{', '}', '[', ']', '*', '&', '!', '|', '>', '\'', '"', '%', '@', '`', '-', '?', ':' -> true;
            default -> s.contains(": ") || s.contains(" #");
        };
    }

    private static ScalarStyle mapStyle(DumperOptions.ScalarStyle style) {
        return switch (style) {
            case SINGLE_QUOTED -> ScalarStyle.SINGLE_QUOTED;
            case DOUBLE_QUOTED -> ScalarStyle.DOUBLE_QUOTED;
            case LITERAL -> ScalarStyle.LITERAL;
            case FOLDED -> ScalarStyle.FOLDED;
            case PLAIN -> ScalarStyle.PLAIN;
        };
    }

    private static DumperOptions.ScalarStyle toSnakeStyle(ScalarStyle style) {
        return switch (style) {
            case SINGLE_QUOTED -> DumperOptions.ScalarStyle.SINGLE_QUOTED;
            case DOUBLE_QUOTED -> DumperOptions.ScalarStyle.DOUBLE_QUOTED;
            case LITERAL -> DumperOptions.ScalarStyle.LITERAL;
            case FOLDED -> DumperOptions.ScalarStyle.FOLDED;
            case PLAIN -> DumperOptions.ScalarStyle.PLAIN;
        };
    }

    private static NodeComments commentsOf(Node node) {
        List<String> block = extractCommentLines(node.getBlockComments());
        List<String> inline = extractCommentLines(node.getInLineComments());
        List<String> end = extractCommentLines(node.getEndComments());
        if (block.isEmpty() && inline.isEmpty() && end.isEmpty()) {
            return NodeComments.EMPTY;
        }
        return new NodeComments(block, inline, end);
    }

    private static List<String> extractCommentLines(@Nullable List<CommentLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(lines.size());
        for (CommentLine line : lines) {
            if (line.getCommentType() == CommentType.BLANK_LINE) {
                out.add("");
            } else {
                String text = line.getValue();
                if (text.startsWith(" ")) {
                    text = text.substring(1);
                }
                out.add(text);
            }
        }
        return out;
    }

    private static void applyComments(Node node, NodeComments comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        if (!comments.block().isEmpty()) {
            node.setBlockComments(toCommentLines(comments.block(), CommentType.BLOCK));
        }
        if (!comments.inline().isEmpty()) {
            node.setInLineComments(toCommentLines(comments.inline(), CommentType.IN_LINE));
        }
        if (!comments.end().isEmpty()) {
            node.setEndComments(toCommentLines(comments.end(), CommentType.BLOCK));
        }
    }

    private static List<CommentLine> toCommentLines(List<String> lines, CommentType defaultType) {
        List<CommentLine> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || (line.isEmpty() && defaultType == CommentType.BLOCK)) {
                out.add(new CommentLine(null, null, "", CommentType.BLANK_LINE));
            } else {
                String normalized = line.startsWith(" ") ? line : " " + line;
                out.add(new CommentLine(null, null, normalized, defaultType));
            }
        }
        return out;
    }

    private static SourceSpan spanOf(String sourceName, Node node, String[] lines) {
        return spanFromMark(sourceName, node.getStartMark(), node.getEndMark(), lines);
    }

    private static SourceSpan spanFromMark(
            String sourceName,
            @Nullable Mark startMark,
            @Nullable Mark endMark,
            String[] lines
    ) {
        if (startMark == null) {
            return SourceSpan.ofSource(sourceName);
        }
        int line = startMark.getLine() + 1;
        int col = startMark.getColumn() + 1;
        int endCol = col + 1;
        if (endMark != null && endMark.getLine() == startMark.getLine() && endMark.getColumn() > startMark.getColumn()) {
            endCol = endMark.getColumn() + 1;
        }
        String lineText = (line >= 1 && line <= lines.length) ? lines[line - 1] : null;
        return new SourceSpan(sourceName, line, col, endCol, lineText);
    }

    private static final class ScalarExtractor extends SafeConstructor {
        ScalarExtractor(LoaderOptions options) {
            super(options);
        }

        void flatten(MappingNode node) {
            flattenMapping(node, false);
        }

        @Nullable Object extract(Node node) {
            return constructObject(node);
        }
    }
}
