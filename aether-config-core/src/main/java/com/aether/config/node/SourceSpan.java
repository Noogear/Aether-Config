package com.aether.config.node;

import org.jetbrains.annotations.Nullable;

/**
 * 1-based source location metadata preserved from the parsed configuration file.
 *
 * @param source    file name or logical source identifier
 * @param line      1-based line number, or 0 if unknown
 * @param column    1-based start column, or 0 if unknown
 * @param endColumn 1-based exclusive end column on the start line, or 0 if unknown
 * @param lineText  raw source line text for snippet rendering, or {@code null}
 */
public record SourceSpan(
        String source,
        int line,
        int column,
        int endColumn,
        @Nullable String lineText
) {

    public static final SourceSpan UNKNOWN = new SourceSpan("<unknown>", 0, 0, 0, null);

    public static SourceSpan ofSource(String source) {
        return new SourceSpan(source != null ? source : "<unknown>", 0, 0, 0, null);
    }

    public boolean isKnown() {
        return line > 0;
    }

    public String locationLabel() {
        if (line > 0 && column > 0) {
            return source + ":" + line + ":" + column;
        }
        if (line > 0) {
            return source + ":" + line;
        }
        return source;
    }
}
