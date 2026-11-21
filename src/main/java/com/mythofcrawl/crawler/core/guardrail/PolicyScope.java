package com.mythofcrawl.crawler.core.guardrail;

/**
 * Scope hierarchy for Action Guardrail policies (most specific wins after #1 ALLOW).
 */
public enum PolicyScope {
    GLOBAL(1, "Toàn cục (Global)"),
    METHOD_OR_ACTION(2, "Method / UI Action"),
    BRANCH(3, "Nhánh UI (Branch)"),
    PATH_PATTERN(4, "Mẫu đường dẫn (Pattern)"),
    EXACT_API(5, "API cụ thể (Exact)");

    public final int specificity;
    public final String label;

    PolicyScope(int specificity, String label) {
        this.specificity = specificity;
        this.label = label;
    }

    public static PolicyScope fromString(String raw, PolicyScope fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return PolicyScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
