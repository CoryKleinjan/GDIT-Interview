package com.gdit.fafsa.engine;

import static com.gdit.fafsa.engine.Outcome.FAILED;
import static com.gdit.fafsa.engine.Outcome.NOT_APPLICABLE;
import static com.gdit.fafsa.engine.Outcome.PASSED;
import static com.gdit.fafsa.engine.Severity.ERROR;
import static com.gdit.fafsa.engine.Severity.INFO;
import static com.gdit.fafsa.engine.Severity.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class EditEngineTest {

    private static final Schema SCHEMA = new Schema(List.of(new FieldDefinition("count", FieldType.INTEGER, null)));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EditEngine engine = new EditEngine(meters);

    @Test
    void hasNoFieldsOrRulesUntilSomeAreLoaded() {
        assertThat(engine.ruleset()).isEqualTo(Ruleset.EMPTY);
        assertThat(engine.validate(count(1))).isEqualTo(new ValidationResult(OverallStatus.VALID, List.of()));
    }

    @Test
    void appliesEveryRuleInOrderAndCollectsAllResults() {
        load(notApplicable("c"), failing("b", ERROR), passing("a"));

        assertThat(engine.validate(count(1)).results())
                .extracting(RuleResult::ruleId, RuleResult::outcome)
                .containsExactly(tuple("c", NOT_APPLICABLE), tuple("b", FAILED), tuple("a", PASSED));
    }

    @Test
    void rulesSeeTheTypedValuesReadByTheSchema() {
        load(rule("small", ERROR, app -> true, app -> (Long) app.get("count") < 10));

        assertThat(engine.validate(count(3)).status()).isEqualTo(OverallStatus.VALID);
        assertThat(engine.validate(count(30)).status()).isEqualTo(OverallStatus.INVALID);
    }

    @Test
    void rejectsAnApplicationThatDoesNotFitTheSchema() {
        load(passing("a"));
        ObjectNode bad = JsonNodeFactory.instance.objectNode().put("count", "many");

        assertThatThrownBy(() -> engine.validate(bad))
                .isInstanceOfSatisfying(InvalidApplicationException.class,
                        ex -> assertThat(ex.problems()).containsExactly("count must be a whole number"));
    }

    @Test
    void failedResultCarriesMessageSeverityAndFields() {
        load(failing("b", WARNING));

        assertThat(engine.validate(count(1)).results())
                .containsExactly(new RuleResult("b", FAILED, WARNING, "b message", List.of("b.field")));
    }

    @Test
    void severityAndMessageAreReportedOnlyForFailures() {
        load(passing("a"), notApplicable("c"));

        assertThat(engine.validate(count(1)).results())
                .containsExactly(new RuleResult("a", PASSED, null, null, List.of("a.field")),
                        new RuleResult("c", NOT_APPLICABLE, null, null, List.of("c.field")));
    }

    @Test
    void replacingTheRulesetTakesEffectOnTheNextValidation() {
        load(failing("old", ERROR));
        load(passing("new"));

        assertThat(engine.ruleset().rules()).extracting(EditRule::id).containsExactly("new");
        assertThat(engine.validate(count(1)).results()).extracting(RuleResult::ruleId).containsExactly("new");
    }

    @Test
    void anyFailedErrorMakesTheApplicationInvalid() {
        load(failing("b", ERROR), failing("w", WARNING));

        assertThat(engine.validate(count(1)).status()).isEqualTo(OverallStatus.INVALID);
    }

    @Test
    void failedWarningsWithoutErrorsAreValidWithWarnings() {
        load(failing("w", WARNING), passing("a"));

        assertThat(engine.validate(count(1)).status()).isEqualTo(OverallStatus.VALID_WITH_WARNINGS);
    }

    @Test
    void failedInfoRulesLeaveTheApplicationValid() {
        load(failing("i", INFO), passing("a"));

        assertThat(engine.validate(count(1)).status()).isEqualTo(OverallStatus.VALID);
    }

    @Test
    void validatesABatchInParallelKeepingRequestOrder() {
        load(rule("even", ERROR, app -> true, app -> (Long) app.get("count") % 2 == 0));
        List<JsonNode> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(count(i));
        }
        var executor = Executors.newFixedThreadPool(4);

        List<ValidationResult> results = engine.validateAll(batch, executor);

        executor.shutdown();
        for (int i = 0; i < 50; i++) {
            assertThat(results.get(i).status()).isEqualTo(i % 2 == 0 ? OverallStatus.VALID : OverallStatus.INVALID);
        }
    }

    @Test
    void rejectsTheWholeBatchNamingEachUnreadableApplicationByIndex() {
        load(passing("a"));
        ObjectNode bad = JsonNodeFactory.instance.objectNode().put("count", "many");

        assertThatThrownBy(() -> engine.validateAll(List.of(count(1), bad, bad), Runnable::run))
                .isInstanceOfSatisfying(InvalidApplicationException.class, ex -> assertThat(ex.problems())
                        .containsExactly("applications[1].count must be a whole number",
                                "applications[2].count must be a whole number"));
    }

    @Test
    void countsValidationsByStatusAndFailuresByRule() {
        load(failing("b", ERROR), passing("a"));

        engine.validate(count(1));
        engine.validate(count(1));

        assertThat(meters.counter("fafsa.applications.validated", "status", "INVALID").count()).isEqualTo(2);
        assertThat(meters.counter("fafsa.edits.failed", "rule", "b", "severity", "ERROR").count()).isEqualTo(2);
        assertThat(meters.find("fafsa.edits.failed").tag("rule", "a").counter()).isNull();
    }

    private void load(EditRule... rules) {
        engine.replace(new Ruleset(SCHEMA, List.of(rules)));
    }

    private static JsonNode count(int count) {
        return JsonNodeFactory.instance.objectNode().put("count", count);
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

    private static EditRule rule(String id, Severity severity, Predicate<Application> appliesTo,
            Predicate<Application> check) {
        return new EditRule(id, severity, id + " message", List.of(id + ".field"), appliesTo, check);
    }
}
