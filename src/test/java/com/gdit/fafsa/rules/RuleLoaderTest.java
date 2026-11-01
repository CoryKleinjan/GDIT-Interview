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
                rules:
                  - id: second-in-name-first-in-file
                    severity: warning
                    message: Check the SSN.
                    fields: [studentInfo.ssn]
                    check: { type: matches, regex: '\\d{9}' }
                  - id: turned-off
                    severity: ERROR
                    enabled: false
                    message: Off.
                    fields: [studentInfo.ssn]
                    required: true
                  - id: a-married-rule
                    severity: ERROR
                    message: Spouse SSN required.
                    fields: [spouseInfo.ssn]
                    required: true
                    when: { field: maritalStatus, equals: married }
                """));

        assertThat(rules).extracting(EditRule::id).containsExactly("second-in-name-first-in-file", "a-married-rule");
        assertThat(rules.get(0).severity()).isEqualTo(Severity.WARNING);
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
        assertProblems(yaml("rules: []"), "rules must not be empty");
    }

    @Test
    void reportsEveryMissingRequiredPropertyAtOnce() {
        assertProblems(yaml("""
                rules:
                  - id: incomplete
                    required: true
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
        assertProblems(yaml("""
                rules:
                  - id: r
                    severity: ERROR
                    message: m
                    fields: [studentInfo.middleName]
                    check: { type: atMost, field: household.pets }
                    when: { field: favoriteColor, equals: blue }
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
        assertProblems(yaml("""
                rules:
                  - { id: r, severity: ERROR, message: m, fields: [studentInfo.ssn], required: true }
                  - { id: r, severity: ERROR, message: m, fields: [stateOfResidence], required: true }
                """), "Rule 'r' is defined more than once");
    }

    /** A single rule on studentInfo.ssn with one extra YAML line. */
    private static Resource rule(String extraLine) {
        return yaml("""
                rules:
                  - id: r
                    severity: ERROR
                    message: m
                    fields: [studentInfo.ssn]
                    %s
                """.formatted(extraLine));
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
