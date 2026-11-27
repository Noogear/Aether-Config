package com.aether.config.migration;

import com.aether.config.node.ConfigDocument;

import java.util.Map;
import java.util.TreeMap;

/**
 * Ordered sequence of versioned schema migration steps ({@code v1 -> v2 -> v3}).
 */
public final class MigrationChain {

    private final Map<Integer, Entry> steps;

    private MigrationChain(Map<Integer, Entry> steps) {
        this.steps = Map.copyOf(steps);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MigrationChain empty() {
        return new MigrationChain(Map.of());
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * Applies matching migration hops starting at {@code fromVersion} up to {@code targetVersion}.
     *
     * @return the final reached version after applying all applicable hops
     */
    public int apply(ConfigDocument document, int fromVersion, int targetVersion) {
        int current = fromVersion;
        while (current < targetVersion) {
            Entry step = steps.get(current);
            if (step == null || step.toVersion <= current || step.toVersion > targetVersion) {
                break;
            }
            step.action.migrate(document);
            current = step.toVersion;
        }
        return current;
    }

    private record Entry(int toVersion, MigrationStep action) {
    }

    public static final class Builder {
        private final Map<Integer, Entry> steps = new TreeMap<>();

        public Builder step(int fromVersion, int toVersion, MigrationStep action) {
            if (toVersion <= fromVersion) {
                throw new IllegalArgumentException("toVersion (" + toVersion + ") must be greater than fromVersion (" + fromVersion + ")");
            }
            steps.put(fromVersion, new Entry(toVersion, action));
            return this;
        }

        public MigrationChain build() {
            return new MigrationChain(steps);
        }
    }
}
