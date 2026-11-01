package com.gdit.fafsa.rules;

import com.gdit.fafsa.model.FafsaApplication;
import jakarta.validation.constraints.NotBlank;

/** {@code when: { field: maritalStatus, equals: married }}. Compared case-insensitively; a missing value never matches. */
public record Condition(@NotBlank String field, @NotBlank String equals) {

    boolean test(FafsaApplication application) {
        return equals.equalsIgnoreCase(String.valueOf(Fields.read(field, application)));
    }
}
