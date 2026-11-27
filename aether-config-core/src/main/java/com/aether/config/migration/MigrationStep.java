package com.aether.config.migration;

import com.aether.config.node.ConfigDocument;

/**
 * Functional schema migration step operating directly on a comment-preserving {@link ConfigDocument}.
 */
@FunctionalInterface
public interface MigrationStep {

    void migrate(ConfigDocument document);
}
