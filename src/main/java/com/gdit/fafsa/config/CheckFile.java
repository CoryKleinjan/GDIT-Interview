package com.gdit.fafsa.config;

import static java.util.Objects.requireNonNullElse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * The root of {@code checks.yml}: named checks that rules can use like built-in ones.
 *
 * @param examplesAsOf the date examples are evaluated on, so age examples do not go stale
 */
record CheckFile(@NotNull LocalDate examplesAsOf, List<@Valid CheckDefinition> checks) {

    CheckFile {
        checks = requireNonNullElse(checks, List.of());
    }
}
