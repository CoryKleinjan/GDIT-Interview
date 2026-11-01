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
 * on/off switch live under {@code fafsa.edits.rules.<id>} in {@code application.yml}. Declaration order is the
 * order results are reported in.
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

    /** The 50 states, DC, and the territories FAFSA accepts as a state of legal residence. */
    private static final Set<String> STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY",
            "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND",
            "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            "DC", "AS", "FM", "GU", "MH", "MP", "PR", "PW", "VI");

    @Bean
    public EditRule stateCode() {
        return EditRule.of("state-code",
                application -> isOneOf(application.stateOfResidence(), STATE_CODES),
                "stateOfResidence");
    }

    @Bean
    public EditRule marriedSpouseInfo() {
        return EditRule.of("married-spouse-info",
                        application -> {
                            var spouse = application.spouseInfo();
                            return allHaveText(spouse.firstName(), spouse.lastName(), spouse.ssn());
                        },
                        "spouseInfo.firstName", "spouseInfo.lastName", "spouseInfo.ssn")
                .onlyWhen(FafsaApplication::isMarried);
    }

    private static final int MAXIMUM_PLAUSIBLE_AGE = 120;

    /** Not one of the seven required edits: a WARNING-level sanity check that shows non-blocking severities. */
    @Bean
    public EditRule studentAgePlausible(Clock clock) {
        return EditRule.of("student-age-plausible",
                        application -> ageInYears(application.studentInfo().dateOfBirth(), clock)
                                <= MAXIMUM_PLAUSIBLE_AGE,
                        "studentInfo.dateOfBirth")
                .onlyWhen(application -> application.studentInfo().dateOfBirth() != null);
    }
}
