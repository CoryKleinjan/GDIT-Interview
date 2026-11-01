package com.gdit.fafsa.rules;

import static java.util.Objects.requireNonNullElse;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Severity;
import com.gdit.fafsa.model.FafsaApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

/**
 * One rule as written in the rule file. Every listed field must pass: if it is missing (null or blank) it fails
 * only when {@code required} is true; if it is present it must satisfy {@code check}, when there is one.
 * {@code examples} are the rule's own test cases, checked by the loader.
 */
public record RuleDefinition(
        @NotBlank String id,
        @NotNull Severity severity,
        Boolean enabled,
        @NotBlank String message,
        @NotEmpty List<@NotBlank String> fields,
        boolean required,
        @Valid Check check,
        @Valid Condition when,
        @NotNull @Valid Examples examples) {

    public RuleDefinition {
        enabled = requireNonNullElse(enabled, true);
    }

    EditRule toEditRule(Clock clock) {
        Predicate<FafsaApplication> appliesTo = when == null ? application -> true : when::test;
        return new EditRule(id, severity, message, fields, appliesTo, application -> {
            var context = new RuleContext(application, LocalDate.now(clock));
            return fields.stream().allMatch(field -> isValid(Fields.read(field, application), context));
        });
    }

    private boolean isValid(Object value, RuleContext context) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return !required;
        }
        return check == null || check.test(value, context);
    }
}
