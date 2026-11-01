package com.gdit.fafsa.engine;

import static com.gdit.fafsa.engine.Outcome.FAILED;
import static com.gdit.fafsa.engine.Outcome.NOT_APPLICABLE;
import static com.gdit.fafsa.engine.Outcome.PASSED;
import static com.gdit.fafsa.engine.Severity.ERROR;
import static com.gdit.fafsa.engine.Severity.INFO;
import static com.gdit.fafsa.engine.Severity.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.groups.Tuple.tuple;

import com.gdit.fafsa.model.FafsaApplication;
import com.gdit.fafsa.model.FafsaApplication.DependencyStatus;
import com.gdit.fafsa.model.FafsaApplication.MaritalStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EditEngineTest {

    private static final FafsaApplication APPLICATION =
            new FafsaApplication(null, DependencyStatus.INDEPENDENT, MaritalStatus.SINGLE, null, null, null, null);

    private static final EditRule PASSING = EditRule.of("passing", app -> true, "a");
    private static final EditRule FAILING = EditRule.of("failing", app -> false, "b", "c");
    private static final EditRule SKIPPED = EditRule.of("skipped", app -> false, "d").onlyWhen(app -> false);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void appliesEveryRuleInRegistrationOrderAndCollectsAllResults() {
        var engine = engine(List.of(SKIPPED, FAILING, PASSING),
                settings("passing", ERROR, "failing", ERROR, "skipped", ERROR));

        assertThat(engine.validate(APPLICATION).results())
                .extracting(RuleResult::ruleId, RuleResult::outcome)
                .containsExactly(
                        tuple("skipped", NOT_APPLICABLE),
                        tuple("failing", FAILED),
                        tuple("passing", PASSED));
    }

    @Test
    void failedResultCarriesConfiguredMessageSeverityAndFields() {
        var engine = engine(List.of(FAILING), settings("failing", WARNING));

        assertThat(engine.validate(APPLICATION).results())
                .containsExactly(new RuleResult("failing", FAILED, WARNING, "failing message", List.of("b", "c")));
    }

    @Test
    void passedAndNotApplicableResultsHaveNoMessage() {
        var engine = engine(List.of(PASSING, SKIPPED), settings("passing", ERROR, "skipped", ERROR));

        assertThat(engine.validate(APPLICATION).results()).extracting(RuleResult::message).containsOnlyNulls();
    }

    @Test
    void disabledRulesAreNotApplied() {
        var properties = new EditRulesProperties(Map.of(
                "passing", new RuleSettings(ERROR, false, "off"),
                "failing", new RuleSettings(ERROR, true, "on")));

        var engine = new EditEngine(List.of(PASSING, FAILING), properties, meters);

        assertThat(engine.validate(APPLICATION).results()).extracting(RuleResult::ruleId).containsExactly("failing");
    }

    @Test
    void anyFailedErrorMakesTheApplicationInvalid() {
        var engine = engine(List.of(FAILING, failing("warn")), settings("failing", ERROR, "warn", WARNING));

        assertThat(engine.validate(APPLICATION).status()).isEqualTo(OverallStatus.INVALID);
    }

    @Test
    void failedWarningsWithoutErrorsAreValidWithWarnings() {
        var engine = engine(List.of(FAILING, PASSING), settings("failing", WARNING, "passing", ERROR));

        assertThat(engine.validate(APPLICATION).status()).isEqualTo(OverallStatus.VALID_WITH_WARNINGS);
    }

    @Test
    void failedInfoRulesAndPassingRulesLeaveTheApplicationValid() {
        var engine = engine(List.of(FAILING, PASSING), settings("failing", INFO, "passing", ERROR));

        assertThat(engine.validate(APPLICATION).status()).isEqualTo(OverallStatus.VALID);
    }

    @Test
    void refusesToStartWhenARuleHasNoConfiguration() {
        assertThatIllegalStateException()
                .isThrownBy(() -> engine(List.of(PASSING, FAILING), settings("passing", ERROR)))
                .withMessageContaining("failing");
    }

    @Test
    void refusesToStartWhenConfigurationNamesAnUnknownRule() {
        assertThatIllegalStateException()
                .isThrownBy(() -> engine(List.of(PASSING), settings("passing", ERROR, "typo", ERROR)))
                .withMessageContaining("typo");
    }

    @Test
    void refusesToStartWhenTwoRulesShareAnId() {
        assertThatIllegalStateException()
                .isThrownBy(() -> engine(List.of(PASSING, PASSING), settings("passing", ERROR)));
    }

    @Test
    void countsValidationsByStatusAndFailuresByRule() {
        var engine = engine(List.of(FAILING, PASSING), settings("failing", ERROR, "passing", ERROR));

        engine.validate(APPLICATION);
        engine.validate(APPLICATION);

        assertThat(meters.counter("fafsa.applications.validated", "status", "INVALID").count()).isEqualTo(2);
        assertThat(meters.counter("fafsa.edits.failed", "rule", "failing", "severity", "ERROR").count()).isEqualTo(2);
        assertThat(meters.find("fafsa.edits.failed").tag("rule", "passing").counter()).isNull();
    }

    private EditEngine engine(List<EditRule> rules, Map<String, RuleSettings> settings) {
        return new EditEngine(rules, new EditRulesProperties(settings), meters);
    }

    private static EditRule failing(String id) {
        return EditRule.of(id, app -> false, "x");
    }

    /** Builds enabled settings in the given order, e.g. settings("a", ERROR, "b", WARNING). */
    private static Map<String, RuleSettings> settings(Object... idSeverityPairs) {
        var settings = new LinkedHashMap<String, RuleSettings>();
        for (int i = 0; i < idSeverityPairs.length; i += 2) {
            String id = (String) idSeverityPairs[i];
            settings.put(id, new RuleSettings((Severity) idSeverityPairs[i + 1], true, id + " message"));
        }
        return settings;
    }
}
