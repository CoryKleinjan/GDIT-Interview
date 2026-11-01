package com.gdit.fafsa.rules;

import static com.gdit.fafsa.model.ApplicationBuilder.aValidApplication;
import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Severity;
import com.gdit.fafsa.model.ApplicationBuilder;
import com.gdit.fafsa.model.FafsaApplication.MaritalStatus;
import com.gdit.fafsa.model.FafsaApplication.SpouseInfo;
import com.gdit.fafsa.rules.Check.Matches;
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
        var rule = definition(List.of("studentInfo.ssn"), true, null, null);

        assertThat(passes(rule, aValidApplication().ssn(ssn))).isFalse();
    }

    @Test
    void requiredRuleWithoutACheckPassesWhenPresent() {
        var rule = definition(List.of("studentInfo.ssn"), true, null, null);

        assertThat(passes(rule, aValidApplication().ssn("anything"))).isTrue();
    }

    @Test
    void optionalRulePassesWhenTheValueIsMissing() {
        var rule = definition(List.of("studentInfo.ssn"), false, NINE_DIGITS, null);

        assertThat(passes(rule, aValidApplication().ssn(null))).isTrue();
    }

    @Test
    void presentValuesMustPassTheCheck() {
        var rule = definition(List.of("studentInfo.ssn"), false, NINE_DIGITS, null);

        assertThat(passes(rule, aValidApplication().ssn("123456789"))).isTrue();
        assertThat(passes(rule, aValidApplication().ssn("12345"))).isFalse();
    }

    @Test
    void everyListedFieldMustPass() {
        var rule = definition(List.of("income.studentIncome", "income.parentIncome"), false,
                new Check.Min(BigDecimal.ZERO), null);

        assertThat(passes(rule, aValidApplication().income(BigDecimal.ONE, BigDecimal.ONE))).isTrue();
        assertThat(passes(rule, aValidApplication().income(BigDecimal.ONE, new BigDecimal("-1")))).isFalse();
    }

    @Test
    void conditionComparesTheFieldCaseInsensitively() {
        var rule = definition(List.of("spouseInfo.ssn"), true, null, new Condition("maritalStatus", "married"));

        assertThat(applies(rule, aValidApplication().married(new SpouseInfo("J", "S", "1")))).isTrue();
        assertThat(applies(rule, aValidApplication().maritalStatus(MaritalStatus.SINGLE))).isFalse();
        assertThat(applies(rule, aValidApplication().maritalStatus(null))).isFalse();
    }

    @Test
    void ruleWithoutAConditionAlwaysApplies() {
        assertThat(applies(definition(List.of("studentInfo.ssn"), true, null, null), aValidApplication())).isTrue();
    }

    @Test
    void carriesItsMetadataAndDefaultsToEnabled() {
        var definition = definition(List.of("studentInfo.ssn"), true, null, null);
        EditRule rule = definition.toEditRule(Clock.systemUTC());

        assertThat(definition.enabled()).isTrue();
        assertThat(rule.id()).isEqualTo("rule");
        assertThat(rule.severity()).isEqualTo(Severity.WARNING);
        assertThat(rule.message()).isEqualTo("Fix it.");
        assertThat(rule.fields()).containsExactly("studentInfo.ssn");
    }

    private static RuleDefinition definition(List<String> fields, boolean required, Check check, Condition when) {
        return new RuleDefinition("rule", Severity.WARNING, null, "Fix it.", fields, required, check, when);
    }

    private static boolean passes(RuleDefinition definition, ApplicationBuilder application) {
        return definition.toEditRule(Clock.systemUTC()).check().test(application.build());
    }

    private static boolean applies(RuleDefinition definition, ApplicationBuilder application) {
        return definition.toEditRule(Clock.systemUTC()).appliesTo().test(application.build());
    }
}
