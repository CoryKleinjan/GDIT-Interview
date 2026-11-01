package com.gdit.fafsa.rules;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/** Small, null-safe building blocks shared by the edit rules. */
final class Checks {

    private Checks() {
    }

    static int ageInYears(LocalDate dateOfBirth, Clock clock) {
        return Period.between(dateOfBirth, LocalDate.now(clock)).getYears();
    }

    /** False for null, so a missing value fails a format check. */
    static boolean matches(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
    }

    /** True for null: a missing amount is not a negative one (whether it is required is a separate rule). */
    static boolean isNonNegative(BigDecimal amount) {
        return amount == null || amount.signum() >= 0;
    }

    static boolean isOneOf(String value, Set<String> allowed) {
        return value != null && allowed.contains(value);
    }
}
