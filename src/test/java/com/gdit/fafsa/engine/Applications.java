package com.gdit.fafsa.engine;

import java.util.HashMap;
import java.util.Map;

/** Test helper: an already-typed application from path/value pairs, e.g. {@code application("count", 4L)}. Nulls are left out. */
public final class Applications {

    private Applications() {
    }

    public static Application application(Object... pathValuePairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pathValuePairs.length; i += 2) {
            if (pathValuePairs[i + 1] != null) {
                values.put((String) pathValuePairs[i], pathValuePairs[i + 1]);
            }
        }
        return new Application(values);
    }
}
