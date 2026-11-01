package com.gdit.fafsa.engine;

import java.util.List;

/** A request that cannot be evaluated, with every field problem found. Problems never include submitted values. */
public class InvalidApplicationException extends RuntimeException {

    private final List<String> problems;

    public InvalidApplicationException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
