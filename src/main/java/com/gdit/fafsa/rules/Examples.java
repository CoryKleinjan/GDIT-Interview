package com.gdit.fafsa.rules;

import static java.util.Objects.requireNonNullElse;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gdit.fafsa.engine.Outcome;
import com.gdit.fafsa.model.FafsaApplication;
import jakarta.validation.constraints.NotEmpty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A rule's own test cases: partial applications, written as {@code field path: value}, that the rule must pass,
 * fail, or not apply to. The loader checks them every time the rule file is loaded.
 */
public record Examples(
        @NotEmpty List<Map<String, Object>> pass,
        @NotEmpty List<Map<String, Object>> fail,
        List<Map<String, Object>> notApplicable) {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

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

    /** Builds an application from {@code {"studentInfo.ssn": "123456789", ...}}; unset fields stay empty. */
    static FafsaApplication toApplication(Map<String, Object> fieldValues) {
        Map<String, Object> json = new HashMap<>();
        fieldValues.forEach((path, value) -> {
            String[] names = path.split("\\.");
            Map<String, Object> section = json;
            for (int i = 0; i < names.length - 1; i++) {
                section = section(section, names[i]);
            }
            section.put(names[names.length - 1], value);
        });
        return JSON.convertValue(json, FafsaApplication.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String name) {
        return (Map<String, Object>) parent.computeIfAbsent(name, key -> new HashMap<String, Object>());
    }
}
