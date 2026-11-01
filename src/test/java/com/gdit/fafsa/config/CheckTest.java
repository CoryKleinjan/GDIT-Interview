package com.gdit.fafsa.config;

import static com.gdit.fafsa.engine.Applications.application;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.config.Check.AllOf;
import com.gdit.fafsa.config.Check.AnyOf;
import com.gdit.fafsa.config.Check.AtMost;
import com.gdit.fafsa.config.Check.Matches;
import com.gdit.fafsa.config.Check.MaxAge;
import com.gdit.fafsa.config.Check.Min;
import com.gdit.fafsa.config.Check.MinAge;
import com.gdit.fafsa.config.Check.Named;
import com.gdit.fafsa.config.Check.Not;
import com.gdit.fafsa.config.Check.OneOf;
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
        return new RuleContext(application("household.numberInHousehold", 4L), TODAY);
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
        assertThat(check.referencedFields()).containsExactly(java.util.Map.entry("household.numberInHousehold", Number.class));
    }

    @Test
    void atMostFailsWhenTheOtherFieldIsMissing() {
        var context = new RuleContext(application(), TODAY);

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
    void leafChecksReferenceNothingElseAndNeedNoResolving() {
        var check = new Min(BigDecimal.ONE);

        assertThat(check.referencedFields()).isEmpty();
        assertThat(check.names()).isEmpty();
        assertThat(check.consistent()).isTrue();
        assertThat(check.resolve(name -> null)).isSameAs(check);
    }

    @Test
    void allOfPassesOnlyWhenEveryCheckPasses() {
        var check = new AllOf(List.of(new Min(BigDecimal.ZERO), new AtMost("household.numberInHousehold")));

        assertThat(check.test(3L, context())).isTrue();
        assertThat(check.test(-1L, context())).isFalse();
        assertThat(check.test(5L, context())).isFalse();
        assertThat(check.valueType()).isEqualTo(Number.class);
        assertThat(check.referencedFields()).containsOnlyKeys("household.numberInHousehold");
    }

    @Test
    void anyOfPassesWhenAnyCheckPasses() {
        var check = new AnyOf(List.of(new OneOf(List.of("CA")), new Matches(Pattern.compile("N."))));

        assertThat(check.test("CA", context())).isTrue();
        assertThat(check.test("NY", context())).isTrue();
        assertThat(check.test("TX", context())).isFalse();
    }

    @Test
    void notInvertsItsCheck() {
        var check = new Not(new Matches(Pattern.compile("9.*")));

        assertThat(check.test("123", context())).isTrue();
        assertThat(check.test("923", context())).isFalse();
        assertThat(check.valueType()).isEqualTo(String.class);
        assertThat(check.referencedFields()).isEmpty();
        assertThat(check.consistent()).isTrue();
    }

    @Test
    void combinationsOfDifferentValueTypesAreInconsistent() {
        assertThat(new AllOf(List.of(new Min(BigDecimal.ZERO), new MinAge(14))).consistent()).isFalse();
        assertThat(new AnyOf(List.of(new Min(BigDecimal.ZERO), new Not(new AllOf(
                List.of(new MinAge(1), new Min(BigDecimal.ONE)))))).consistent()).isFalse();
    }

    @Test
    void namedChecksAreReplacedByTheirDefinitionsWhereverTheyAppear() {
        Check nineDigits = new Matches(Pattern.compile("\\d{9}"));
        Check check = new Not(new AnyOf(List.of(new Named("nineDigits"), new AllOf(List.of(new Named("nineDigits"))))));

        assertThat(check.names()).containsExactly("nineDigits", "nineDigits");
        Check resolved = check.resolve(name -> nineDigits);

        assertThat(resolved).isEqualTo(new Not(new AnyOf(List.of(nineDigits, new AllOf(List.of(nineDigits))))));
        assertThat(resolved.names()).isEmpty();
        assertThat(resolved.test("123456789", context())).isFalse();
    }

    @Test
    void anUnresolvedNamedCheckCannotBeUsed() {
        var check = new Named("nineDigits");

        assertThatIllegalStateException().isThrownBy(check::valueType).withMessageContaining("nineDigits");
        assertThatIllegalStateException().isThrownBy(() -> check.test("1", context()));
    }
}
