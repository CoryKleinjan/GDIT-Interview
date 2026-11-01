package com.gdit.fafsa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.Severity;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class RuleLoaderTest {

    private final RuleLoader loader =
            new RuleLoader(Validation.buildDefaultValidatorFactory().getValidator(), Clock.systemUTC());

    @Test
    void loadsEnabledRulesInFileOrder() {
        List<EditRule> rules = loader.load(yaml("""
                examplesAsOf: 2026-11-01
                rules:
                  - id: second-in-name-first-in-file
                    severity: warning
                    message: Check the SSN.
                    fields: [studentInfo.ssn]
                    check: { type: matches, regex: '\\d{9}' }
                    examples: { pass: [ { studentInfo.ssn: '123456789' } ], fail: [ { studentInfo.ssn: '1' } ] }
                  - id: turned-off
                    severity: ERROR
                    enabled: false
                    message: Off.
                    fields: [studentInfo.ssn]
                    required: true
                    examples: { pass: [ { studentInfo.ssn: '1' } ], fail: [ {} ] }
                  - id: a-married-rule
                    severity: ERROR
                    message: Spouse SSN required.
                    fields: [spouseInfo.ssn]
                    required: true
                    when: { field: maritalStatus, equals: married }
                    examples:
                      pass: [ { maritalStatus: married, spouseInfo.ssn: '1' } ]
                      fail: [ { maritalStatus: married } ]
                      notApplicable: [ { maritalStatus: single } ]
                """));

        assertThat(rules).extracting(EditRule::id).containsExactly("second-in-name-first-in-file", "a-married-rule");
        assertThat(rules.get(0).severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void evaluatesExamplesAsOfTheFilesDateNotToday() {
        List<EditRule> rules = loader.load(yaml("""
                examplesAsOf: 2000-01-01
                rules:
                  - id: age
                    severity: ERROR
                    message: m
                    fields: [studentInfo.dateOfBirth]
                    check: { type: minAge, years: 14 }
                    examples:
                      pass: [ { studentInfo.dateOfBirth: 1986-01-01 } ]
                      fail: [ { studentInfo.dateOfBirth: 1986-01-02 } ]
                """));

        assertThat(rules).extracting(EditRule::id).containsExactly("age");
    }

    @Test
    void rejectsARuleThatDoesNotBehaveAsItsExamplesSay() {
        assertProblems(file("""
                  - id: r
                    severity: ERROR
                    message: m
                    fields: [studentInfo.ssn]
                    required: true
                    when: { field: maritalStatus, equals: married }
                    examples:
                      pass: [ { maritalStatus: married } ]
                      fail: [ { maritalStatus: married, studentInfo.ssn: '1' } ]
                      notApplicable: [ { maritalStatus: married, studentInfo.ssn: '1' } ]
                """),
                "Rule 'r' example pass[0] {maritalStatus=married} should be PASSED but was FAILED",
                "Rule 'r' example fail[0] {maritalStatus=married, studentInfo.ssn=1} should be FAILED but was PASSED",
                "Rule 'r' example notApplicable[0] {maritalStatus=married, studentInfo.ssn=1} should be NOT_APPLICABLE");
    }

    @Test
    void rejectsExamplesWithUnknownFieldsOrUnusableValues() {
        assertProblems(file("""
                  - id: r
                    severity: ERROR
                    message: m
                    fields: [studentInfo.ssn]
                    required: true
                    examples:
                      pass: [ { studentInfo.middleName: x } ]
                      fail: [ { household.numberInHousehold: lots } ]
                """),
                "Rule 'r' example pass[0] uses unknown field 'studentInfo.middleName'",
                "Rule 'r' example fail[0] has a value that does not fit the application");
    }

    @Test
    void requiresAPassingAndAFailingExampleForEveryRule() {
        assertProblems(file("""
                  - { id: none, severity: ERROR, message: m, fields: [studentInfo.ssn], required: true }
                  - id: fail-only
                    severity: ERROR
                    message: m
                    fields: [studentInfo.ssn]
                    required: true
                    examples: { pass: [], fail: [ {} ] }
                """),
                "rules[0].examples must not be null",
                "rules[1].examples.pass must not be empty");
    }

    @Test
    void requiresTheDateExamplesAreEvaluatedAsOf() {
        assertProblems(yaml("rules:\n" + ssnRule("r", "")), "examplesAsOf must not be null");
    }

    @Test
    void rejectsUnparseableYaml() {
        assertProblems(yaml("rules: [ { id: x"), "but got <stream end>");
    }

    @Test
    void rejectsUnknownPropertiesSoTyposAreNotSilentlyIgnored() {
        assertProblems(rule("requird: true"), "Unrecognized field \"requird\"");
    }

    @Test
    void rejectsUnknownCheckTypes() {
        assertProblems(rule("check: { type: isPrime }"), "known type ids = [atMost, matches, maxAge, min, minAge, oneOf]");
    }

    @Test
    void rejectsInvalidRegularExpressions() {
        assertProblems(rule("check: { type: matches, regex: '[unclosed' }"), "Unclosed character class");
    }

    @Test
    void rejectsAMissingFile() {
        Resource missing = new FileSystemResource("does/not/exist.yml");

        assertThatThrownBy(() -> loader.load(missing))
                .isInstanceOf(InvalidRulesException.class)
                .hasMessageContaining("does/not/exist.yml");
    }

    @Test
    void rejectsAnEmptyRuleList() {
        assertProblems(yaml("examplesAsOf: 2026-11-01\nrules: []"), "rules must not be empty");
    }

    @Test
    void reportsEveryMissingRequiredPropertyAtOnce() {
        assertProblems(file("""
                  - id: incomplete
                    required: true
                    examples: { pass: [ {} ], fail: [ {} ] }
                """),
                "rules[0].severity must not be null",
                "rules[0].message must not be blank",
                "rules[0].fields must not be empty");
    }

    @Test
    void rejectsAnInvalidCheck() {
        assertProblems(rule("check: { type: oneOf, values: [] }"), "rules[0].check.values must not be empty");
    }

    @Test
    void rejectsARuleThatChecksNothing() {
        assertProblems(rule(""), "Rule 'r' needs 'required: true', a 'check', or both");
    }

    @Test
    void rejectsUnknownFieldsWhereverTheyAreUsed() {
        assertProblems(file("""
                  - id: r
                    severity: ERROR
                    message: m
                    fields: [studentInfo.middleName]
                    check: { type: atMost, field: household.pets }
                    when: { field: favoriteColor, equals: blue }
                    examples: { pass: [ {} ], fail: [ {} ] }
                """),
                "Rule 'r' uses unknown field 'studentInfo.middleName'",
                "Rule 'r' uses unknown field 'household.pets'",
                "Rule 'r' uses unknown field 'favoriteColor'");
    }

    @Test
    void rejectsACheckOnTheWrongTypeOfField() {
        assertProblems(rule("check: { type: minAge, years: 14 }"),
                "Rule 'r' applies a LocalDate check to 'studentInfo.ssn', which is a String");
    }

    @Test
    void rejectsDuplicateIds() {
        assertProblems(file(ssnRule("r", "required: true") + ssnRule("r", "required: true")),
                "Rule 'r' is defined more than once");
    }

    /** A single rule on studentInfo.ssn with one extra YAML line. */
    private static Resource rule(String extraLine) {
        return file(ssnRule("r", extraLine));
    }

    private static String ssnRule(String id, String extraLine) {
        return """
                  - id: %s
                    severity: ERROR
                    message: m
                    fields: [studentInfo.ssn]
                    examples: { pass: [ { studentInfo.ssn: '123456789' } ], fail: [ {} ] }
                    %s
                """.formatted(id, extraLine);
    }

    private static Resource file(String rules) {
        return yaml("examplesAsOf: 2026-11-01\nrules:\n" + rules);
    }

    private static Resource yaml(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), "test rules");
    }

    private void assertProblems(Resource resource, String... expectedProblems) {
        assertThatThrownBy(() -> loader.load(resource))
                .isInstanceOfSatisfying(InvalidRulesException.class, ex -> {
                    for (String expected : expectedProblems) {
                        assertThat(ex.problems()).anySatisfy(problem -> assertThat(problem).contains(expected));
                    }
                })
                .hasMessageContaining("test rules");
    }
}
