package com.gdit.fafsa.rules;

import static com.gdit.fafsa.rules.Checks.*;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.model.FafsaApplication;
import java.time.Clock;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The FAFSA edit rules, one bean per rule. Each bean holds only the rule's logic; its severity, message and
 * on/off switch live under {@code fafsa.edits.rules.<id>} in {@code application.yml}.
 *
 * <p>To add a rule: add a bean here, an entry in {@code application.yml}, and a test in FafsaEditRulesTest.
 */
@Configuration
public class FafsaEditRules {

    /** "Today" comes from this clock so age rules can be tested against a fixed date. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    private static final int MINIMUM_AGE = 14;

    @Bean
    public EditRule studentAge(Clock clock) {
        return EditRule.of("student-age",
                application -> {
                    var dateOfBirth = application.studentInfo().dateOfBirth();
                    return dateOfBirth != null && ageInYears(dateOfBirth, clock) >= MINIMUM_AGE;
                },
                "studentInfo.dateOfBirth");
    }

    private static final Pattern NINE_DIGITS = Pattern.compile("\\d{9}");

    @Bean
    public EditRule ssnFormat() {
        return EditRule.of("ssn-format",
                application -> matches(application.studentInfo().ssn(), NINE_DIGITS),
                "studentInfo.ssn");
    }

    @Bean
    public EditRule dependentParentIncome() {
        return EditRule.of("dependent-parent-income",
                        application -> application.income().parentIncome() != null,
                        "income.parentIncome")
                .onlyWhen(FafsaApplication::isDependent);
    }

    @Bean
    public EditRule nonNegativeIncome() {
        return EditRule.of("non-negative-income",
                application -> isNonNegative(application.income().studentIncome())
                        && isNonNegative(application.income().parentIncome()),
                "income.studentIncome", "income.parentIncome");
    }

    @Bean
    public EditRule householdCollegeCount() {
        return EditRule.of("household-college-count",
                application -> {
                    var household = application.household();
                    return household.numberInHousehold() != null
                            && household.numberInCollege() != null
                            && household.numberInCollege() <= household.numberInHousehold();
                },
                "household.numberInCollege", "household.numberInHousehold");
    }
}
