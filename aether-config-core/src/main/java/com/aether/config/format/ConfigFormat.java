package com.aether.config.format;

import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.node.ConfigNode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Locale;
import java.util.Set;

/**
 * Pluggable text serialization format SPI (YAML, JSON, TOML, etc.) operating on {@link ConfigNode.MapNode}.
 */
public interface ConfigFormat {

    ConfigNode.MapNode parse(String sourceName, Reader reader, ConfigDiagnostics diagnostics);

    void write(ConfigNode.MapNode root, Writer writer) throws IOException;

    Set<String> extensions();

    default boolean supportsFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : extensions()) {
            if (lower.endsWith("." + ext.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
