package com.gdit.fafsa.engine;

import java.util.List;

/**
 * The result of applying one edit rule.
 *
 * @param message what is wrong and how to fix it; present only when the rule failed
 * @param fields  JSON paths of the fields the rule checks, so a client can highlight them
 */
public record RuleResult(String ruleId, Outcome outcome, Severity severity, String message, List<String> fields) {

    public boolean failed() {
        return outcome == Outcome.FAILED;
    }
}
