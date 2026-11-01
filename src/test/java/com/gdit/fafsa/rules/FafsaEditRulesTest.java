package com.gdit.fafsa.rules;

import static com.gdit.fafsa.model.ApplicationBuilder.aValidApplication;
import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.model.ApplicationBuilder;
import com.gdit.fafsa.model.FafsaApplication.SpouseInfo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Rule logic only: plain JUnit, no Spring context. Severity and messages are covered by the engine and API tests. */
class FafsaEditRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final FafsaEditRules rules = new FafsaEditRules();

    private static boolean passes(EditRule rule, ApplicationBuilder application) {
        return rule.check().test(application.build());
    }

    private static boolean applies(EditRule rule, ApplicationBuilder application) {
        return rule.appliesTo().test(application.build());
    }

    @Nested
    class StudentAge {

        private final EditRule rule = rules.studentAge(CLOCK);

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

        private final EditRule rule = rules.ssnFormat();

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

        private final EditRule rule = rules.dependentParentIncome();

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
        }
    }

    @Nested
    class NonNegativeIncome {

        private final EditRule rule = rules.nonNegativeIncome();

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

        private final EditRule rule = rules.householdCollegeCount();

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

        private final EditRule rule = rules.stateCode();

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

        private final EditRule rule = rules.marriedSpouseInfo();

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
        }
    }
}
