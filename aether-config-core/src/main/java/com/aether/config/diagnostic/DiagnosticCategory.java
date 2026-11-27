package com.aether.config.diagnostic;

/**
 * Classification of configuration diagnostic origins.
 */
public enum DiagnosticCategory {
    SYNTAX("Syntax"),
    TYPE("Type"),
    VALIDATION("Validation"),
    SCHEMA("Schema"),
    MIGRATION("Migration"),
    INTERPOLATION("Interpolation"),
    IO("IO");

    private final String label;

    DiagnosticCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
