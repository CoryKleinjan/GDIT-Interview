package com.gdit.fafsa.config;

import static java.util.Objects.requireNonNullElse;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Severity;
import com.gdit.fafsa.engine.Application;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
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

    /** This rule with named checks replaced by their definitions. */
    RuleDefinition resolve(Function<String, Check> definitions) {
        Check resolved = check == null ? null : check.resolve(definitions);
        return new RuleDefinition(id, severity, enabled, message, fields, required, resolved, when, examples);
    }

    EditRule toEditRule(Clock clock) {
        Predicate<Application> appliesTo = when == null ? application -> true : when::test;
        return new EditRule(id, severity, message, fields, appliesTo, application -> {
            var context = new RuleContext(application, LocalDate.now(clock));
            return fields.stream().allMatch(field -> isValid(application.get(field), context));
        });
    }

    private boolean isValid(Object value, RuleContext context) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return !required;
        }
        return check == null || check.test(value, context);
    }
}
