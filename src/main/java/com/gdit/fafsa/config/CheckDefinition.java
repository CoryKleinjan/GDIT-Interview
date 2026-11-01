package com.gdit.fafsa.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A named check in {@code checks.yml}, built from built-in checks and checks defined above it, with examples of
 * values it must pass and fail when applied to {@code examples.field}.
 */
public record CheckDefinition(
        @NotBlank String name,
        String description,
        @NotNull @Valid Check check,
        @NotNull @Valid CheckExamples examples) {

    public record CheckExamples(@NotBlank String field, @NotEmpty List<Object> pass, @NotEmpty List<Object> fail) {
    }
}
