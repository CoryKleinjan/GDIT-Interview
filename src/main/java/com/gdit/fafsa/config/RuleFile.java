package com.gdit.fafsa.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * The root of a rule file. An empty list is rejected so a bad edit cannot silently switch every rule off.
 *
 * @param examplesAsOf the date examples are evaluated on, so age examples do not go stale as time passes
 */
record RuleFile(@NotNull LocalDate examplesAsOf, @NotEmpty List<@Valid RuleDefinition> rules) {
}
