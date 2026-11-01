package com.gdit.fafsa.rules;

import static com.gdit.fafsa.model.ApplicationBuilder.aValidApplication;
import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Severity;
import com.gdit.fafsa.model.ApplicationBuilder;
import com.gdit.fafsa.model.FafsaApplication.SpouseInfo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import jakarta.validation.Validation;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rules shipped in {@code rules.yml}, loaded exactly as the service loads them but with a fixed clock.
 * Plain JUnit, no Spring context. One nested class per rule, covering its boundaries.
 */
class ShippedRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private static final List<EditRule> RULES =
            new RuleLoader(Validation.buildDefaultValidatorFactory().getValidator(), CLOCK)
                    .load(new ClassPathResource("rules.yml"));

    private static EditRule rule(String id) {
        return RULES.stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void rulesRunInPriorityOrderWithStatusesFirstAndHouseholdAfterMaritalStatus() {
        assertThat(RULES).extracting(EditRule::id).containsExactly(
                "marital-status", "dependency-status", "student-age", "ssn-format", "dependent-parent-income",
                "non-negative-income", "married-spouse-info", "household-college-count",
                "married-household-includes-spouse", "state-code", "student-age-plausible");
    }

    @Test
    void onlyThePlausibilityCheckIsAWarning() {
        assertThat(RULES).filteredOn(rule -> rule.severity() == Severity.WARNING)
                .extracting(EditRule::id).containsExactly("student-age-plausible");
    }

    private static boolean passes(EditRule rule, ApplicationBuilder application) {
        return rule.check().test(application.build());
    }

    private static boolean applies(EditRule rule, ApplicationBuilder application) {
        return rule.appliesTo().test(application.build());
    }

    @Nested
    class MaritalStatusRequired {

        private final EditRule rule = rule("marital-status");

        @Test
        void passesForSingleOrMarried() {
            assertThat(passes(rule, aValidApplication())).isTrue();
            assertThat(passes(rule, aValidApplication().married(null))).isTrue();
        }

        @Test
        void failsWhenMissing() {
            assertThat(passes(rule, aValidApplication().maritalStatus(null))).isFalse();
        }
    }

    @Nested
    class DependencyStatusRequired {

        private final EditRule rule = rule("dependency-status");

        @Test
        void passesForDependentOrIndependent() {
            assertThat(passes(rule, aValidApplication())).isTrue();
            assertThat(passes(rule, aValidApplication().independent())).isTrue();
        }

        @Test
        void failsWhenMissing() {
            assertThat(passes(rule, aValidApplication().dependencyStatus(null))).isFalse();
        }
    }

    @Nested
    class MarriedHouseholdIncludesSpouse {

        private final EditRule rule = rule("married-household-includes-spouse");

        @ParameterizedTest(name = "household={0}")
        @ValueSource(ints = {2, 5})
        void passesWhenHouseholdCountsStudentAndSpouse(int household) {
            assertThat(passes(rule, aValidApplication().married(null).household(household, 1))).isTrue();
        }

        @Test
        void failsWhenAMarriedStudentsHouseholdIsOne() {
            assertThat(passes(rule, aValidApplication().married(null).household(1, 1))).isFalse();
        }

        @Test
        void failsWhenHouseholdIsMissing() {
            assertThat(passes(rule, aValidApplication().married(null).household(null, 1))).isFalse();
        }

        @Test
        void appliesOnlyToMarriedStudents() {
            assertThat(applies(rule, aValidApplication().married(null))).isTrue();
            assertThat(applies(rule, aValidApplication())).isFalse();
        }
    }

    @Nested
    class StudentAge {

        private final EditRule rule = rule("student-age");

        @Test
        void passesOnTheFourteenthBirthday() {
            assertThat(passes(rule, aValidApplication().dateOfBirth(TODAY.minusYears(14)))).isTrue();
        }

        @Test
        void failsOneDayBeforeTheFourteenthBirthday() {
            assertThat(passes(rule, aValidApplication().dateOfBirth(TODAY.minusYears(14).plusDays(1)))).isFalse();
        }

        @Test
        void failsForADateOfBirthInTheFuture() {
            assertThat(passes(rule, aValidApplication().dateOfBirth(TODAY.plusDays(1)))).isFalse();
        }

        @Test
        void failsWhenDateOfBirthIsMissing() {
            assertThat(passes(rule, aValidApplication().dateOfBirth(null))).isFalse();
        }
    }

    @Nested
    class SsnFormat {

        private final EditRule rule = rule("ssn-format");

        @ParameterizedTest
        @ValueSource(strings = {"123456789", "987654321"})
        void passesForExactlyNineDigits(String ssn) {
            assertThat(passes(rule, aValidApplication().ssn(ssn))).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"12345678", "1234567890", "123-45-6789", "12345678a", " 123456789", "invalid"})
        void failsForAnythingElse(String ssn) {
            assertThat(passes(rule, aValidApplication().ssn(ssn))).isFalse();
        }
    }

    @Nested
    class DependentParentIncome {

        private final EditRule rule = rule("dependent-parent-income");

        @Test
        void passesWhenADependentReportsParentIncome() {
            assertThat(passes(rule, aValidApplication())).isTrue();
        }

        @Test
        void passesWhenParentIncomeIsZero() {
            assertThat(passes(rule, aValidApplication().income(BigDecimal.ONE, BigDecimal.ZERO))).isTrue();
        }

        @Test
        void failsWhenADependentOmitsParentIncome() {
            assertThat(passes(rule, aValidApplication().income(BigDecimal.ONE, null))).isFalse();
        }

        @Test
        void appliesOnlyToDependentStudents() {
            assertThat(applies(rule, aValidApplication())).isTrue();
            assertThat(applies(rule, aValidApplication().independent())).isFalse();
            assertThat(applies(rule, aValidApplication().dependencyStatus(null))).isFalse();
        }
    }

    @Nested
    class NonNegativeIncome {

        private final EditRule rule = rule("non-negative-income");

        @ParameterizedTest(name = "student={0}, parent={1}")
        @CsvSource({"0, 0", "5000, 65000", "5000,", ", 65000", ","})
        void passesForZeroPositiveOrMissingIncome(BigDecimal studentIncome, BigDecimal parentIncome) {
            assertThat(passes(rule, aValidApplication().income(studentIncome, parentIncome))).isTrue();
        }

        @ParameterizedTest(name = "student={0}, parent={1}")
        @CsvSource({"-1, 0", "0, -1", "-0.01,", "-1000, -1000"})
        void failsWhenAnyIncomeIsNegative(BigDecimal studentIncome, BigDecimal parentIncome) {
            assertThat(passes(rule, aValidApplication().income(studentIncome, parentIncome))).isFalse();
        }
    }

    @Nested
    class HouseholdCollegeCount {

        private final EditRule rule = rule("household-college-count");

        @ParameterizedTest(name = "household={0}, college={1}")
        @CsvSource({"4, 1", "2, 2", "1, 0"})
        void passesWhenCollegeCountFitsInHousehold(Integer household, Integer college) {
            assertThat(passes(rule, aValidApplication().household(household, college))).isTrue();
        }

        @ParameterizedTest(name = "household={0}, college={1}")
        @CsvSource({"2, 5", "2, 3", ", 1", "4,", ","})
        void failsWhenCollegeExceedsHouseholdOrACountIsMissing(Integer household, Integer college) {
            assertThat(passes(rule, aValidApplication().household(household, college))).isFalse();
        }
    }

    @Nested
    class StateCode {

        private final EditRule rule = rule("state-code");

        @ParameterizedTest
        @ValueSource(strings = {"CA", "NY", "WY", "DC", "PR", "GU"})
        void passesForUsStatesDcAndTerritories(String state) {
            assertThat(passes(rule, aValidApplication().stateOfResidence(state))).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"XX", "ca", "CAL", " CA", "California"})
        void failsForAnythingElse(String state) {
            assertThat(passes(rule, aValidApplication().stateOfResidence(state))).isFalse();
        }
    }

    @Nested
    class MarriedSpouseInfo {

        private final EditRule rule = rule("married-spouse-info");

        @Test
        void passesWhenSpouseNameAndSsnArePresent() {
            assertThat(passes(rule, aValidApplication().married(new SpouseInfo("John", "Smith", "987654321")))).isTrue();
        }

        @Test
        void failsWhenSpouseSectionIsMissing() {
            assertThat(passes(rule, aValidApplication().married(null))).isFalse();
        }

        @ParameterizedTest(name = "first={0}, last={1}, ssn={2}")
        @CsvSource({", Smith, 987654321", "John, , 987654321", "John, Smith,", "'  ', Smith, 987654321"})
        void failsWhenAnySpouseFieldIsBlank(String firstName, String lastName, String ssn) {
            assertThat(passes(rule, aValidApplication().married(new SpouseInfo(firstName, lastName, ssn)))).isFalse();
        }

        @Test
        void appliesOnlyToMarriedStudents() {
            assertThat(applies(rule, aValidApplication().married(null))).isTrue();
            assertThat(applies(rule, aValidApplication())).isFalse();
            assertThat(applies(rule, aValidApplication().maritalStatus(null))).isFalse();
        }
    }

    @Nested
    class StudentAgePlausible {

        private final EditRule rule = rule("student-age-plausible");

        @Test
        void passesAtAge120() {
            assertThat(passes(rule, aValidApplication().dateOfBirth(TODAY.minusYears(121).plusDays(1)))).isTrue();
        }

        @Test
        void failsAtAge121() {
            assertThat(passes(rule, aValidApplication().dateOfBirth(TODAY.minusYears(121)))).isFalse();
        }

        @Test
        void passesWhenDateOfBirthIsMissingBecauseStudentAgeReportsThat() {
            assertThat(passes(rule, aValidApplication().dateOfBirth(null))).isTrue();
        }
    }
}
