package com.aether.config.migration;

import com.aether.config.annotation.Version;
import com.aether.config.bind.NamingStrategy;
import com.aether.config.bind.PropertySchema;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticCategory;
import com.aether.config.node.ConfigDocument;
import com.aether.config.node.SourceSpan;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Evaluates {@link Version} metadata on configuration schemas, triggers backups, and runs {@link MigrationChain} steps.
 */
public final class VersionManager {

    private VersionManager() {
    }

    public record VersionDescriptor(int expectedVersion, String key, boolean autoBackup) {
    }

    public static @Nullable VersionDescriptor inspect(Class<?> configType, NamingStrategy naming) {
        Version typeAnn = configType.getAnnotation(Version.class);
        if (typeAnn != null) {
            String key = typeAnn.key().isEmpty() ? "config-version" : typeAnn.key();
            return new VersionDescriptor(typeAnn.value(), key, typeAnn.autoBackup());
        }
        for (PropertySchema prop : PropertySchema.forType(configType)) {
            if (prop.version() != null) {
                Version ann = prop.version();
                String key = ann.key().isEmpty() ? prop.resolveKey(naming) : ann.key();
                return new VersionDescriptor(ann.value(), key, ann.autoBackup());
            }
        }
        return null;
    }

    public static boolean migrateIfNeeded(
            Class<?> configType,
            @Nullable Path sourceFile,
            boolean isNewFile,
            ConfigDocument document,
            NamingStrategy naming,
            @Nullable MigrationChain chain,
            ConfigDiagnostics diagnostics
    ) {
        VersionDescriptor desc = inspect(configType, naming);
        if (desc == null || desc.expectedVersion() < 0) {
            return false;
        }
        if (isNewFile) {
            if (!document.has(desc.key())) {
                document.setRaw(desc.key(), desc.expectedVersion());
            }
            return false;
        }

        int actualVersion = document.has(desc.key()) ? document.getInt(desc.key(), 0) : 0;
        if (actualVersion == desc.expectedVersion()) {
            return false;
        }

        SourceSpan span = sourceFile != null
                ? SourceSpan.ofSource(sourceFile.getFileName().toString())
                : SourceSpan.UNKNOWN;

        if (actualVersion > desc.expectedVersion()) {
            diagnostics.warn(
                    DiagnosticCategory.MIGRATION,
                    span,
                    desc.key(),
                    "Configuration file version " + actualVersion + " is newer than schema version " + desc.expectedVersion() + "."
            );
            return false;
        }

        if (desc.autoBackup() && sourceFile != null) {
            try {
                Path backupPath = ConfigBackup.backup(sourceFile, "v" + actualVersion);
                if (backupPath != null) {
                    diagnostics.info(
                            DiagnosticCategory.MIGRATION,
                            span,
                            desc.key(),
                            "Backed up v" + actualVersion + " configuration to " + backupPath.getFileName() + "."
                    );
                }
            } catch (Exception ex) {
                diagnostics.error(
                        DiagnosticCategory.MIGRATION,
                        span,
                        desc.key(),
                        "Failed to back up configuration before migration: " + ex.getMessage()
                );
                return false;
            }
        }

        int stampedVersion = desc.expectedVersion();
        if (chain != null && !chain.isEmpty()) {
            try {
                int reached = chain.apply(document, actualVersion, desc.expectedVersion());
                if (reached < desc.expectedVersion()) {
                    diagnostics.warn(
                            DiagnosticCategory.MIGRATION,
                            span,
                            desc.key(),
                            "Migration chain from v" + actualVersion + " to v" + desc.expectedVersion()
                                    + " stopped at v" + reached + " due to missing migration step."
                    );
                    stampedVersion = reached;
                }
            } catch (Exception ex) {
                diagnostics.error(
                        DiagnosticCategory.MIGRATION,
                        span,
                        desc.key(),
                        "Migration v" + actualVersion + " -> v" + desc.expectedVersion() + " failed: " + ex.getMessage()
                );
                return false;
            }
        }

        document.setRaw(desc.key(), stampedVersion);
        if (stampedVersion != actualVersion) {
            diagnostics.info(
                    DiagnosticCategory.MIGRATION,
                    span,
                    desc.key(),
                    "Migrated configuration from v" + actualVersion + " to v" + stampedVersion + "."
            );
        }
        return stampedVersion != actualVersion;
    }
}
