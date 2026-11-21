package com.mythofcrawl.crawler.core.guardrail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-Java deterministic evaluator for Action Guardrail policies.
 * Enforces strict #1 ALLOW precedence across all scopes, then evaluates blocking/holding
 * rules by scope specificity (EXACT_API > PATH_PATTERN > BRANCH > METHOD_OR_ACTION > GLOBAL)
 * and descending priority.
 */
public final class PolicyEvaluator {

    public static final class Decision {
        public final boolean allowed;
        public final GuardrailMode effectiveMode;
        public final PolicyRule winningRule;
        public final String reason;
        public final long evaluatedAtMs;
        public final List<PolicyRule> matchedCandidates;

        public Decision(
            boolean allowed,
            GuardrailMode effectiveMode,
            PolicyRule winningRule,
            String reason,
            List<PolicyRule> matchedCandidates
        ) {
            this.allowed = allowed;
            this.effectiveMode = effectiveMode != null ? effectiveMode : GuardrailMode.NONE;
            this.winningRule = winningRule;
            this.reason = reason != null ? reason : "";
            this.evaluatedAtMs = System.currentTimeMillis();
            this.matchedCandidates = matchedCandidates != null ? matchedCandidates : List.of();
        }
    }

    private static final Comparator<PolicyRule> SPECIFICITY_COMPARATOR = (a, b) -> {
        int s = Integer.compare(b.scope.specificity, a.scope.specificity);
        if (s != 0) {
            return s;
        }
        return Integer.compare(b.priority, a.priority);
    };

    private PolicyEvaluator() {
    }

    /**
     * Evaluates a live request/action against the ledger and increments the winning rule hit counter.
     */
    public static Decision evaluate(
        PolicyLedger ledger,
        String branch,
        String host,
        String method,
        String path,
        String uiAction
    ) {
        return evaluateInternal(ledger, branch, host, method, path, uiAction, true);
    }

    /**
     * Simulates policy resolution without mutating rule hit counters (for Policy Tester UI).
     */
    public static Decision simulate(
        PolicyLedger ledger,
        String branch,
        String host,
        String method,
        String path,
        String uiAction
    ) {
        return evaluateInternal(ledger, branch, host, method, path, uiAction, false);
    }

    private static Decision evaluateInternal(
        PolicyLedger ledger,
        String branch,
        String host,
        String method,
        String path,
        String uiAction,
        boolean recordHit
    ) {
        if (ledger == null) {
            return new Decision(true, GuardrailMode.NONE, null, "No ledger attached", List.of());
        }
        List<PolicyRule> all = ledger.snapshotRules();
        List<PolicyRule> allowMatches = new ArrayList<>();
        List<PolicyRule> guardMatches = new ArrayList<>();
        List<PolicyRule> allMatched = new ArrayList<>();

        for (PolicyRule r : all) {
            if (r.matches(branch, host, method, path, uiAction)) {
                allMatched.add(r);
                if (r.allow) {
                    allowMatches.add(r);
                } else if (r.mode != GuardrailMode.NONE) {
                    guardMatches.add(r);
                }
            }
        }

        // Strict #1 Precedence: any matching ALLOW rule cuts across all blocking/holding rules
        if (!allowMatches.isEmpty()) {
            allowMatches.sort(SPECIFICITY_COMPARATOR);
            PolicyRule winner = allowMatches.get(0);
            if (recordHit) {
                winner.recordHit();
            }
            return new Decision(
                true,
                GuardrailMode.NONE,
                winner,
                "Allowed by #1 ALLOW rule [" + winner.name + "]",
                allMatched
            );
        }

        if (!guardMatches.isEmpty()) {
            guardMatches.sort(SPECIFICITY_COMPARATOR);
            PolicyRule winner = guardMatches.get(0);
            if (recordHit) {
                winner.recordHit();
            }
            boolean isAllowed = !winner.mode.isBlockingOrHolding();
            return new Decision(
                isAllowed,
                winner.mode,
                winner,
                "Matched [" + winner.name + "] -> " + winner.mode.shortLabel,
                allMatched
            );
        }

        return new Decision(true, GuardrailMode.NONE, null, "Default allow (NONE)", allMatched);
    }
}
