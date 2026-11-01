package com.gdit.fafsa.rules;

import static com.gdit.fafsa.model.ApplicationBuilder.aValidApplication;
import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.rules.Check.AtMost;
import com.gdit.fafsa.rules.Check.Matches;
import com.gdit.fafsa.rules.Check.MaxAge;
import com.gdit.fafsa.rules.Check.Min;
import com.gdit.fafsa.rules.Check.MinAge;
import com.gdit.fafsa.rules.Check.OneOf;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each check type on its own. Checks only ever see present values; missing values are handled by the rule. */
class CheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static RuleContext context() {
        return new RuleContext(aValidApplication().household(4, 1).build(), TODAY);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"123456789, true", "12345678, false", "123-45-6789, false"})
    void matchesTestsTheWholeValue(String value, boolean expected) {
        var check = new Matches(Pattern.compile("\\d{9}"));

        assertThat(check.test(value, context())).isEqualTo(expected);
        assertThat(check.valueType()).isEqualTo(String.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"CA, true", "XX, false", "ca, false"})
    void oneOfIsExactAndCaseSensitive(String value, boolean expected) {
        var check = new OneOf(List.of("CA", "NY"));

        assertThat(check.test(value, context())).isEqualTo(expected);
        assertThat(check.valueType()).isEqualTo(String.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"0, true", "0.00, true", "1, true", "-0.01, false"})
    void minComparesAnyNumberWithTheBoundIncluded(BigDecimal value, boolean expected) {
        var check = new Min(BigDecimal.ZERO);

        assertThat(check.test(value, context())).isEqualTo(expected);
        assertThat(check.test(value.intValue(), context())).isEqualTo(value.intValue() >= 0);
        assertThat(check.valueType()).isEqualTo(Number.class);
    }

    @ParameterizedTest(name = "{0} vs household of 4 -> {1}")
    @CsvSource({"3, true", "4, true", "5, false"})
    void atMostComparesWithAnotherField(int value, boolean expected) {
        var check = new AtMost("household.numberInHousehold");

        assertThat(check.test(value, context())).isEqualTo(expected);
        assertThat(check.valueType()).isEqualTo(Number.class);
        assertThat(check.referencedFields()).containsExactly("household.numberInHousehold");
    }

    @Test
    void atMostFailsWhenTheOtherFieldIsMissing() {
        var context = new RuleContext(aValidApplication().household(null, 1).build(), TODAY);

        assertThat(new AtMost("household.numberInHousehold").test(1, context)).isFalse();
    }

    @Test
    void minAgeCountsWholeYearsUpToToday() {
        var check = new MinAge(14);

        assertThat(check.test(TODAY.minusYears(14), context())).isTrue();
        assertThat(check.test(TODAY.minusYears(14).plusDays(1), context())).isFalse();
        assertThat(check.test(TODAY.plusDays(1), context())).isFalse();
        assertThat(check.valueType()).isEqualTo(LocalDate.class);
    }

    @Test
    void maxAgeCountsWholeYearsUpToToday() {
        var check = new MaxAge(120);

        assertThat(check.test(TODAY.minusYears(121).plusDays(1), context())).isTrue();
        assertThat(check.test(TODAY.minusYears(121), context())).isFalse();
        assertThat(check.valueType()).isEqualTo(LocalDate.class);
    }

    @Test
    void mostChecksReferenceNoOtherFields() {
        assertThat(new Min(BigDecimal.ONE).referencedFields()).isEmpty();
    }
}
