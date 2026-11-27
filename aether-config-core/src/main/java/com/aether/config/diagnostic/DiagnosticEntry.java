package com.aether.config.diagnostic;

import com.aether.config.node.SourceSpan;
import org.jetbrains.annotations.Nullable;

/**
 * Structured configuration diagnostic entry with optional line snippet and suggestion hint.
 */
public record DiagnosticEntry(
        DiagnosticSeverity severity,
        DiagnosticCategory category,
        SourceSpan span,
        String path,
        String message,
        @Nullable String hint
) {

    public DiagnosticEntry {
        span = span != null ? span : SourceSpan.UNKNOWN;
        path = path != null ? path : "";
        message = message != null ? message : "";
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(severity.name()).append(']')
          .append(" [").append(category.label()).append("] ");

        String loc = span.locationLabel();
        if (!path.isEmpty()) {
            sb.append(loc).append(" (").append(path).append(')');
        } else {
            sb.append(loc);
        }

        if (span.isKnown() && span.lineText() != null && !span.lineText().isEmpty()) {
            String lineNum = String.valueOf(span.line());
            String gutter = "  " + lineNum + " | ";
            sb.append('\n').append(gutter).append(span.lineText());

            if (span.column() > 0) {
                int colOffset = Math.max(0, span.column() - 1);
                int caretLen = span.endColumn() > span.column() ? span.endColumn() - span.column() : 1;
                caretLen = Math.min(caretLen, Math.max(1, span.lineText().length() - colOffset));
                sb.append('\n')
                  .append(" ".repeat(2 + lineNum.length())).append(" | ")
                  .append(" ".repeat(colOffset))
                  .append("^".repeat(caretLen))
                  .append(' ').append(message);
            } else {
                sb.append('\n').append("  ").append(message);
            }
        } else {
            sb.append(": ").append(message);
        }

        if (hint != null && !hint.isBlank()) {
            sb.append("\n  Hint: ").append(hint);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }
}
