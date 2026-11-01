package com.gdit.fafsa.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Ruleset;
import com.gdit.fafsa.engine.Severity;
import jakarta.validation.Validation;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The shipped configuration folder. Loading it is the per-rule and per-check test: the loader checks every named
 * check and every rule against the examples written next to it, so a new one needs only its YAML entry.
 */
class ShippedConfigTest {

    private static final Ruleset CONFIG =
            new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Clock.systemUTC())
                    .load(new ClassPathResource("edits/"));

    private static List<String> ruleIds() {
        return CONFIG.rules().stream().map(EditRule::id).toList();
    }

    @Test
    void everyCheckAndRuleBehavesAsItsExamplesSay() {
        assertThat(CONFIG.rules()).isNotEmpty();
        assertThat(CONFIG.schema().fields()).isNotEmpty();
    }

    @Test
    void rulesRunInPriorityOrderWithStatusesFirstAndHouseholdAfterMaritalStatus() {
        assertThat(ruleIds()).startsWith("marital-status", "dependency-status");
        assertThat(ruleIds()).containsSubsequence("marital-status", "married-spouse-info",
                "household-college-count", "married-household-includes-spouse");
    }

    @Test
    void requiredEditsAreErrorsAndOnlyThePlausibilityCheckIsAWarning() {
        assertThat(CONFIG.rules()).filteredOn(rule -> rule.severity() != Severity.ERROR)
                .extracting(EditRule::id).containsExactly("student-age-plausible");
    }
}
