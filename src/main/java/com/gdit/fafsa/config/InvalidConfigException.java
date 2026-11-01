package com.gdit.fafsa.config;

import java.util.List;

/** Configuration that must not go live, with every problem found in it. */
public class InvalidConfigException extends RuntimeException {

    private final List<String> problems;

    InvalidConfigException(String source, List<String> problems) {
        super("Rejected edit configuration from " + source + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
