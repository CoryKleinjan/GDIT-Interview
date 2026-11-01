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
}
