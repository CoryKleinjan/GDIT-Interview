package com.gdit.fafsa.rules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The root of a rule file. An empty list is rejected so a bad edit cannot silently switch every rule off. */
record RuleFile(@NotEmpty List<@Valid RuleDefinition> rules) {
}
