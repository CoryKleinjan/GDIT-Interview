package com.gdit.fafsa.config;

import com.gdit.fafsa.engine.Application;
import jakarta.validation.constraints.NotBlank;

/** {@code when: { field: maritalStatus, equals: married }}. Compared case-insensitively; a missing value never matches. */
public record Condition(@NotBlank String field, @NotBlank String equals) {

    boolean test(Application application) {
        return equals.equalsIgnoreCase(String.valueOf(application.get(field)));
    }
}
