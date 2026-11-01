package com.gdit.fafsa.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gdit.fafsa.model.FafsaApplication;
import java.util.List;
import java.util.function.Predicate;

/**
 * One edit, ready to run. Built from a rule file at runtime, so rules can change without a deployment.
 *
 * @param id        stable identifier, reported in results
 * @param message   what is wrong and how to fix it, reported when the rule fails
 * @param fields    JSON paths of the fields this rule checks, so a client can highlight them
 * @param appliesTo condition for conditional rules; rules that do not apply report {@link Outcome#NOT_APPLICABLE}
 * @param check     true when the application satisfies the rule
 */
public record EditRule(
        String id,
        Severity severity,
        String message,
        List<String> fields,
        @JsonIgnore Predicate<FafsaApplication> appliesTo,
        @JsonIgnore Predicate<FafsaApplication> check) {

    public RuleResult evaluate(FafsaApplication application) {
        if (!appliesTo.test(application)) {
            return result(Outcome.NOT_APPLICABLE, null);
        }
        if (check.test(application)) {
            return result(Outcome.PASSED, null);
        }
        return result(Outcome.FAILED, message);
    }

    private RuleResult result(Outcome outcome, String message) {
        return new RuleResult(id, outcome, severity, message, fields);
    }
}
