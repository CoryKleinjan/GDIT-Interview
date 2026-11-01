package com.gdit.fafsa.engine;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The configurable part of a rule. Rules are enabled unless {@code enabled: false} is set. */
public record RuleSettings(@NotNull Severity severity, @DefaultValue("true") boolean enabled, @NotBlank String message) {
}
