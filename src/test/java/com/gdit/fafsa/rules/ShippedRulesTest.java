package com.gdit.fafsa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Severity;
import jakarta.validation.Validation;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The shipped {@code rules.yml}. Loading it is the per-rule test: the loader checks every rule against the
 * pass/fail/notApplicable examples written next to it, so a new rule needs only its YAML entry.
 */
class ShippedRulesTest {

    private static final List<EditRule> RULES =
            new RuleLoader(Validation.buildDefaultValidatorFactory().getValidator(), Clock.systemUTC())
                    .load(new ClassPathResource("rules.yml"));

    @Test
    void everyRuleBehavesAsItsExamplesSay() {
        assertThat(RULES).isNotEmpty();
    }

    @Test
    void rulesRunInPriorityOrderWithStatusesFirstAndHouseholdAfterMaritalStatus() {
        List<String> ids = RULES.stream().map(EditRule::id).toList();

        assertThat(ids).startsWith("marital-status", "dependency-status");
        assertThat(ids).containsSubsequence("marital-status", "married-spouse-info",
                "household-college-count", "married-household-includes-spouse");
    }

    @Test
    void requiredEditsAreErrorsAndOnlyThePlausibilityCheckIsAWarning() {
        assertThat(RULES).filteredOn(rule -> rule.severity() != Severity.ERROR)
                .extracting(EditRule::id).containsExactly("student-age-plausible");
    }
}
