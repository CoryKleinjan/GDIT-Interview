package com.gdit.fafsa.engine;

import com.gdit.fafsa.model.FafsaApplication;
import java.util.List;
import java.util.function.Predicate;

/**
 * The logic of one edit: when it applies and what makes it pass. Severity, message and on/off switch live in
 * configuration ({@link RuleSettings}) so they can change without a code change.
 *
 * @param id        stable identifier, also the rule's key under {@code fafsa.edits.rules}
 * @param fields    JSON paths of the fields this rule checks
 * @param appliesTo condition for conditional rules; rules that do not apply report {@link Outcome#NOT_APPLICABLE}
 * @param check     true when the application satisfies the rule
 */
public record EditRule(
        String id, List<String> fields, Predicate<FafsaApplication> appliesTo, Predicate<FafsaApplication> check) {

    public static EditRule of(String id, Predicate<FafsaApplication> check, String... fields) {
        return new EditRule(id, List.of(fields), application -> true, check);
    }

    /** Returns a copy of this rule that is only applied when {@code condition} holds. */
    public EditRule onlyWhen(Predicate<FafsaApplication> condition) {
        return new EditRule(id, fields, condition, check);
    }

    RuleResult evaluate(FafsaApplication application, RuleSettings settings) {
        if (!appliesTo.test(application)) {
            return result(Outcome.NOT_APPLICABLE, settings, null);
        }
        if (check.test(application)) {
            return result(Outcome.PASSED, settings, null);
        }
        return result(Outcome.FAILED, settings, settings.message());
    }

    private RuleResult result(Outcome outcome, RuleSettings settings, String message) {
        return new RuleResult(id, outcome, settings.severity(), message, fields);
    }
}
