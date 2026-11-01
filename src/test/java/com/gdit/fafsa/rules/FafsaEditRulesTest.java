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
}
