package com.gdit.fafsa.config;

import static com.gdit.fafsa.engine.Applications.application;
import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.config.Check.Matches;
import com.gdit.fafsa.config.Check.Min;
import com.gdit.fafsa.config.Check.Named;
import com.gdit.fafsa.engine.Application;
import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Severity;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleDefinitionTest {

    private static final Check NINE_DIGITS = new Matches(Pattern.compile("\\d{9}"));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void requiredRuleFailsWhenAValueIsMissingOrBlank(String ssn) {
        var rule = definition(List.of("ssn"), true, null, null);

        assertThat(passes(rule, application("ssn", ssn))).isFalse();
    }

    @Test
    void requiredRuleWithoutACheckPassesWhenPresent() {
        assertThat(passes(definition(List.of("ssn"), true, null, null), application("ssn", "anything"))).isTrue();
    }

    @Test
    void optionalRulePassesWhenTheValueIsMissing() {
        assertThat(passes(definition(List.of("ssn"), false, NINE_DIGITS, null), application())).isTrue();
    }

    @Test
    void presentValuesMustPassTheCheck() {
        var rule = definition(List.of("ssn"), false, NINE_DIGITS, null);

        assertThat(passes(rule, application("ssn", "123456789"))).isTrue();
        assertThat(passes(rule, application("ssn", "12345"))).isFalse();
    }

    @Test
    void everyListedFieldMustPass() {
        var rule = definition(List.of("student", "parent"), false, new Min(BigDecimal.ZERO), null);

        assertThat(passes(rule, application("student", BigDecimal.ONE, "parent", BigDecimal.ONE))).isTrue();
        assertThat(passes(rule, application("student", BigDecimal.ONE, "parent", new BigDecimal("-1")))).isFalse();
    }

    @Test
    void conditionComparesTheFieldCaseInsensitively() {
        var rule = definition(List.of("spouse"), true, null, new Condition("maritalStatus", "MARRIED"));

        assertThat(applies(rule, application("maritalStatus", "married"))).isTrue();
        assertThat(applies(rule, application("maritalStatus", "single"))).isFalse();
        assertThat(applies(rule, application())).isFalse();
    }

    @Test
    void ruleWithoutAConditionAlwaysApplies() {
        assertThat(applies(definition(List.of("ssn"), true, null, null), application())).isTrue();
    }

    @Test
    void resolvingReplacesNamedChecksAndLeavesRulesWithoutACheckAlone() {
        var named = definition(List.of("ssn"), false, new Named("nineDigits"), null);
        var unchecked = definition(List.of("ssn"), true, null, null);

        assertThat(named.resolve(name -> NINE_DIGITS).check()).isSameAs(NINE_DIGITS);
        assertThat(unchecked.resolve(name -> NINE_DIGITS)).isEqualTo(unchecked);
    }

    @Test
    void carriesItsMetadataAndDefaultsToEnabled() {
        var definition = definition(List.of("ssn"), true, null, null);
        EditRule rule = definition.toEditRule(Clock.systemUTC());

        assertThat(definition.enabled()).isTrue();
        assertThat(rule.id()).isEqualTo("rule");
        assertThat(rule.severity()).isEqualTo(Severity.WARNING);
        assertThat(rule.message()).isEqualTo("Fix it.");
        assertThat(rule.fields()).containsExactly("ssn");
    }

    private static RuleDefinition definition(List<String> fields, boolean required, Check check, Condition when) {
        return new RuleDefinition("rule", Severity.WARNING, null, "Fix it.", fields, required, check, when, null);
    }

    private static boolean passes(RuleDefinition definition, Application application) {
        return definition.toEditRule(Clock.systemUTC()).check().test(application);
    }

    private static boolean applies(RuleDefinition definition, Application application) {
        return definition.toEditRule(Clock.systemUTC()).appliesTo().test(application);
    }
}
