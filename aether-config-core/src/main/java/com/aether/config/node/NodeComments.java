package com.aether.config.node;

import java.util.List;

/**
 * Comments attached to a configuration node or mapping entry.
 *
 * @param block  comment lines appearing above the key/node
 * @param inline comment lines appearing at the end of the line
 * @param end    trailing comment lines at the end of a document or container
 */
public record NodeComments(
        List<String> block,
        List<String> inline,
        List<String> end
) {

    public static final NodeComments EMPTY = new NodeComments(List.of(), List.of(), List.of());

    public NodeComments {
        block = block == null || block.isEmpty() ? List.of() : List.copyOf(block);
        inline = inline == null || inline.isEmpty() ? List.of() : List.copyOf(inline);
        end = end == null || end.isEmpty() ? List.of() : List.copyOf(end);
    }

    public boolean isEmpty() {
        return block.isEmpty() && inline.isEmpty() && end.isEmpty();
    }

    public NodeComments withBlock(List<String> block) {
        return new NodeComments(block, this.inline, this.end);
    }

    public NodeComments withInline(List<String> inline) {
        return new NodeComments(this.block, inline, this.end);
    }
}
