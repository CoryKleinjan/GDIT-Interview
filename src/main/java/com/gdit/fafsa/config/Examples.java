package com.gdit.fafsa.config;

import static java.util.Objects.requireNonNullElse;

import com.gdit.fafsa.engine.Outcome;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/**
 * A rule's own test cases: partial applications, written as {@code field path: value}, that the rule must pass,
 * fail, or not apply to. The loader checks them every time the configuration is loaded.
 */
public record Examples(
        @NotEmpty List<Map<String, Object>> pass,
        @NotEmpty List<Map<String, Object>> fail,
        List<Map<String, Object>> notApplicable) {

    public Examples {
        notApplicable = requireNonNullElse(notApplicable, List.of());
    }

    /** Each group of examples with the outcome it must produce and its name in the file. */
    List<Group> groups() {
        return List.of(
                new Group("pass", Outcome.PASSED, pass),
                new Group("fail", Outcome.FAILED, fail),
                new Group("notApplicable", Outcome.NOT_APPLICABLE, notApplicable));
    }

    record Group(String name, Outcome expected, List<Map<String, Object>> examples) {
    }
}
