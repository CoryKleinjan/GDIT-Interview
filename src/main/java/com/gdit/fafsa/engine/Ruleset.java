package com.gdit.fafsa.engine;

import java.util.List;

/** Everything the engine needs, swapped as one unit so fields and rules always match. */
public record Ruleset(Schema schema, List<EditRule> rules) {

    public static final Ruleset EMPTY = new Ruleset(new Schema(List.of()), List.of());

    public Ruleset {
        rules = List.copyOf(rules);
    }
}
