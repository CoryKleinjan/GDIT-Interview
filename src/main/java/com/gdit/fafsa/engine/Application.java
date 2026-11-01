package com.gdit.fafsa.engine;

import java.util.Map;

/** An application as typed values keyed by field path. A field that was missing or null has no entry. */
public record Application(Map<String, Object> values) {

    public Application {
        values = Map.copyOf(values);
    }

    public Object get(String path) {
        return values.get(path);
    }
}
