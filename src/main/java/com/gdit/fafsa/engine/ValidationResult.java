package com.gdit.fafsa.engine;

import java.util.List;

/** Overall status plus every rule's result, in rule (priority) order. */
public record ValidationResult(OverallStatus status, List<RuleResult> results) {

    static ValidationResult of(List<RuleResult> results) {
        return new ValidationResult(statusOf(results), results);
    }

    private static OverallStatus statusOf(List<RuleResult> results) {
        if (failedWith(Severity.ERROR, results)) {
            return OverallStatus.INVALID;
        }
        if (failedWith(Severity.WARNING, results)) {
            return OverallStatus.VALID_WITH_WARNINGS;
        }
        return OverallStatus.VALID;
    }

    private static boolean failedWith(Severity severity, List<RuleResult> results) {
        return results.stream().anyMatch(result -> result.failed() && result.severity() == severity);
    }
}
