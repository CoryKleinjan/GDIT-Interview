package com.gdit.fafsa.engine;

import static com.gdit.fafsa.engine.Outcome.FAILED;
import static com.gdit.fafsa.engine.Outcome.NOT_APPLICABLE;
import static com.gdit.fafsa.engine.Outcome.PASSED;
import static com.gdit.fafsa.engine.Severity.ERROR;
import static com.gdit.fafsa.engine.Severity.INFO;
import static com.gdit.fafsa.engine.Severity.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.gdit.fafsa.model.FafsaApplication;
import com.gdit.fafsa.model.FafsaApplication.DependencyStatus;
import com.gdit.fafsa.model.FafsaApplication.MaritalStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class EditEngineTest {

    private static final FafsaApplication APPLICATION =
            new FafsaApplication(null, DependencyStatus.INDEPENDENT, MaritalStatus.SINGLE, null, null, null, null);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EditEngine engine = new EditEngine(meters);

    @Test
    void hasNoRulesUntilSomeAreLoaded() {
        assertThat(engine.rules()).isEmpty();
        assertThat(engine.validate(APPLICATION)).isEqualTo(new ValidationResult(OverallStatus.VALID, List.of()));
    }

    @Test
    void appliesEveryRuleInOrderAndCollectsAllResults() {
        engine.replaceRules(List.of(notApplicable("c"), failing("b", ERROR), passing("a")));

        assertThat(engine.validate(APPLICATION).results())
                .extracting(RuleResult::ruleId, RuleResult::outcome)
                .containsExactly(tuple("c", NOT_APPLICABLE), tuple("b", FAILED), tuple("a", PASSED));
    }

    @Test
    void failedResultCarriesMessageSeverityAndFields() {
        engine.replaceRules(List.of(failing("b", WARNING)));

        assertThat(engine.validate(APPLICATION).results())
                .containsExactly(new RuleResult("b", FAILED, WARNING, "b message", List.of("b.field")));
    }

    @Test
    void passedAndNotApplicableResultsHaveNoMessage() {
        engine.replaceRules(List.of(passing("a"), notApplicable("c")));

        assertThat(engine.validate(APPLICATION).results()).extracting(RuleResult::message).containsOnlyNulls();
    }

    @Test
    void replacingRulesTakesEffectOnTheNextValidation() {
        engine.replaceRules(List.of(failing("old", ERROR)));
        engine.replaceRules(List.of(passing("new")));

        assertThat(engine.rules()).extracting(EditRule::id).containsExactly("new");
        assertThat(engine.validate(APPLICATION).results()).extracting(RuleResult::ruleId).containsExactly("new");
    }

    @Test
    void anyFailedErrorMakesTheApplicationInvalid() {
        engine.replaceRules(List.of(failing("b", ERROR), failing("w", WARNING)));

        assertThat(engine.validate(APPLICATION).status()).isEqualTo(OverallStatus.INVALID);
    }

    @Test
    void failedWarningsWithoutErrorsAreValidWithWarnings() {
        engine.replaceRules(List.of(failing("w", WARNING), passing("a")));

        assertThat(engine.validate(APPLICATION).status()).isEqualTo(OverallStatus.VALID_WITH_WARNINGS);
    }

    @Test
    void failedInfoRulesLeaveTheApplicationValid() {
        engine.replaceRules(List.of(failing("i", INFO), passing("a")));

        assertThat(engine.validate(APPLICATION).status()).isEqualTo(OverallStatus.VALID);
    }

    @Test
    void countsValidationsByStatusAndFailuresByRule() {
        engine.replaceRules(List.of(failing("b", ERROR), passing("a")));

        engine.validate(APPLICATION);
        engine.validate(APPLICATION);

        assertThat(meters.counter("fafsa.applications.validated", "status", "INVALID").count()).isEqualTo(2);
        assertThat(meters.counter("fafsa.edits.failed", "rule", "b", "severity", "ERROR").count()).isEqualTo(2);
        assertThat(meters.find("fafsa.edits.failed").tag("rule", "a").counter()).isNull();
    }

    private static EditRule passing(String id) {
        return rule(id, ERROR, app -> true, app -> true);
    }

    private static EditRule failing(String id, Severity severity) {
        return rule(id, severity, app -> true, app -> false);
    }

    private static EditRule notApplicable(String id) {
        return rule(id, ERROR, app -> false, app -> false);
    }

    private static EditRule rule(String id, Severity severity, Predicate<FafsaApplication> appliesTo,
            Predicate<FafsaApplication> check) {
        return new EditRule(id, severity, id + " message", List.of(id + ".field"), appliesTo, check);
    }
}
