package com.gdit.fafsa.rules;

import java.util.List;

/** A rule file that must not go live, with every problem found in it. */
public class InvalidRulesException extends RuntimeException {

    private final List<String> problems;

    InvalidRulesException(String source, List<String> problems) {
        super("Rejected edit rules from " + source + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
