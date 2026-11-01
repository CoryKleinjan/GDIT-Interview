package com.gdit.fafsa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.FieldDefinition;
import com.gdit.fafsa.engine.Ruleset;
import com.gdit.fafsa.engine.Severity;
import jakarta.validation.Validation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

/** Every way the three configuration files can be wrong is rejected, with every problem named at once. */
class ConfigLoaderTest {

    private static final String FIELDS = """
            fields:
              - { path: ssn, type: text }
              - { path: born, type: date }
              - { path: status, type: choice, values: [single, married] }
              - { path: count, type: integer }
              - { path: limit, type: integer }
              - { path: amount, type: number }
            """;
    private static final String NO_CHECKS = "examplesAsOf: 2026-11-01\nchecks: []\n";

    private final ConfigLoader loader =
            new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Clock.systemUTC());

    @TempDir
    private Path directory;

    @BeforeEach
    void writeValidDefaults() throws IOException {
        write("fields.yml", FIELDS);
        write("checks.yml", NO_CHECKS);
        rules(ssnRule("r", "required: true"));
    }

    @Test
    void loadsTheSchemaAndEnabledRulesInFileOrder() throws IOException {
        rules("""
                  - id: second-in-name-first-in-file
                    severity: warning
                    message: Check the SSN.
                    fields: [ssn]
                    check: { type: matches, regex: '\\d{9}' }
                    examples: { pass: [ { ssn: '123456789' } ], fail: [ { ssn: '1' } ] }
                  - id: turned-off
                    severity: ERROR
                    enabled: false
                    message: Off.
                    fields: [ssn]
                    required: true
                    examples: { pass: [ { ssn: '1' } ], fail: [ {} ] }
                  - id: a-married-rule
                    severity: ERROR
                    message: Count required when married.
                    fields: [count]
                    required: true
                    when: { field: status, equals: married }
                    examples:
                      pass: [ { status: MARRIED, count: 2 } ]
                      fail: [ { status: married } ]
                      notApplicable: [ { status: single } ]
                """);

        Ruleset ruleset = load();

        assertThat(ruleset.schema().fields()).extracting(FieldDefinition::path)
                .containsExactly("ssn", "born", "status", "count", "limit", "amount");
        assertThat(ruleset.rules()).extracting(EditRule::id)
                .containsExactly("second-in-name-first-in-file", "a-married-rule");
        assertThat(ruleset.rules().get(0).severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void namesTheFolderWhenItCannotHoldFiles() {
        assertThatThrownBy(() -> loader.load(new ByteArrayResource(new byte[0], "not a folder")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("not a folder");
    }

    @Test
    void namesAMissingFile() throws IOException {
        Files.delete(directory.resolve("checks.yml"));

        assertThatThrownBy(this::load).isInstanceOf(InvalidConfigException.class).hasMessageContaining("checks.yml");
    }

    @Nested
    class Fields {

        @Test
        void rejectsUnknownTypesAndBadPaths() throws IOException {
            write("fields.yml", "fields:\n  - { path: ssn, type: colour }\n");
            assertProblems("not one of the values accepted for Enum class");

            write("fields.yml", "fields:\n  - { path: 'student info', type: text }\n");
            assertProblems("fields[0].path must be dot-separated names");
        }

        @Test
        void rejectsAnEmptySchema() throws IOException {
            write("fields.yml", "fields: []\n");

            assertProblems("fields must not be empty");
        }

        @Test
        void rejectsDuplicatesChoicesWithoutValuesAndValuesWithoutAChoice() throws IOException {
            write("fields.yml", """
                    fields:
                      - { path: ssn, type: text }
                      - { path: ssn, type: text, values: [a] }
                      - { path: status, type: choice }
                      - { path: other, type: choice, values: [] }
                    """);

            assertProblems("Field 'ssn' is defined more than once",
                    "Field 'ssn' has 'values' but is not a choice",
                    "Field 'status' is a choice, so it needs 'values'",
                    "Field 'other' is a choice, so it needs 'values'");
        }

        @Test
        void rejectsAFieldThatIsAlsoASection() throws IOException {
            write("fields.yml", "fields:\n  - { path: household, type: integer }\n  - { path: household.size, type: integer }\n");

            assertProblems("Field 'household' cannot also contain other fields");
        }
    }

    @Nested
    class Checks {

        @Test
        void rulesUseNamedChecksLikeBuiltInOnes() throws IOException {
            write("checks.yml", """
                    examplesAsOf: 2026-11-01
                    checks:
                      - name: nineDigits
                        description: Nine digits.
                        check: { type: matches, regex: '\\d{9}' }
                        examples: { field: ssn, pass: ['123456789'], fail: ['12345'] }
                      - name: plausibleSsn
                        check:
                          type: allOf
                          checks: [ { type: nineDigits }, { type: not, check: { type: matches, regex: '9.*' } } ]
                        examples: { field: ssn, pass: ['123456789'], fail: ['923456789', '12'] }
                    """);
            rules(ssnRule("r", "check: { type: plausibleSsn }").replace(
                    "pass: [ { ssn: '123456789' } ], fail: [ {} ]",
                    "pass: [ { ssn: '123456789' }, {} ], fail: [ { ssn: '987654321' } ]"));

            assertThat(load().rules()).extracting(EditRule::id).containsExactly("r");
        }

        @Test
        void rejectsBuiltInNamesDuplicatesAndChecksNotDefinedAbove() throws IOException {
            write("checks.yml", """
                    examplesAsOf: 2026-11-01
                    checks:
                      - { name: matches, check: { type: min, value: 0 }, examples: { field: count, pass: [1], fail: [-1] } }
                      - { name: first, check: { type: later }, examples: { field: count, pass: [1], fail: [-1] } }
                      - { name: later, check: { type: min, value: 0 }, examples: { field: count, pass: [1], fail: [-1] } }
                      - { name: later, check: { type: min, value: 0 }, examples: { field: count, pass: [1], fail: [-1] } }
                    """);

            assertProblems("Check 'matches' has the name of a built-in check",
                    "Check 'first' uses check 'later', which is not built in or defined above it",
                    "Check 'later' is defined more than once");
        }

        @Test
        void rejectsChecksThatDoNotFitTheirExampleField() throws IOException {
            write("checks.yml", """
                    examplesAsOf: 2026-11-01
                    checks:
                      - name: mixed
                        check: { type: anyOf, checks: [ { type: min, value: 0 }, { type: minAge, years: 1 } ] }
                        examples: { field: count, pass: [1], fail: [-1] }
                      - { name: ageOfText, check: { type: minAge, years: 1 }, examples: { field: ssn, pass: ['1'], fail: ['2'] } }
                      - { name: nowhere, check: { type: min, value: 0 }, examples: { field: height, pass: [1], fail: [-1] } }
                      - { name: limitOfText, check: { type: atMost, field: ssn }, examples: { field: count, pass: [1], fail: [2] } }
                    """);

            assertProblems("Check 'mixed' combines checks for different types of value",
                    "Check 'ageOfText' applies a LocalDate check to 'ssn', which is a String",
                    "Check 'nowhere' uses unknown field 'height'",
                    "Check 'limitOfText' applies a Number check to 'ssn', which is a String");
        }

        @Test
        void rejectsChecksThatDoNotBehaveAsTheirExamplesSay() throws IOException {
            write("checks.yml", """
                    examplesAsOf: 2026-11-01
                    checks:
                      - name: positive
                        check: { type: min, value: 1 }
                        examples: { field: count, pass: [0, lots, null], fail: [5] }
                    """);

            assertProblems("Check 'positive' example pass[0] '0' should pass",
                    "Check 'positive' example pass[1] count must be a whole number",
                    "Check 'positive' example pass[2] is empty; checks only see values that are present",
                    "Check 'positive' example fail[0] '5' should fail");
        }

        @Test
        void evaluatesAgeExamplesAsOfTheFilesDate() throws IOException {
            write("checks.yml", """
                    examplesAsOf: 2000-01-01
                    checks:
                      - { name: fourteen, check: { type: minAge, years: 14 }, examples: { field: born, pass: [1986-01-01], fail: [1986-01-02] } }
                    """);

            assertThat(load().rules()).hasSize(1);
        }

        @Test
        void requiresTheExamplesDateAndExamples() throws IOException {
            write("checks.yml", "checks:\n  - { name: c, check: { type: min, value: 0 } }\n");

            assertProblems("examplesAsOf must not be null", "checks[0].examples must not be null");
        }
    }

    @Nested
    class Rules {

        @Test
        void evaluatesExamplesAsOfTheFilesDateNotToday() throws IOException {
            write("rules.yml", """
                    examplesAsOf: 2000-01-01
                    rules:
                      - id: age
                        severity: ERROR
                        message: m
                        fields: [born]
                        check: { type: minAge, years: 14 }
                        examples: { pass: [ { born: 1986-01-01 } ], fail: [ { born: 1986-01-02 } ] }
                    """);

            assertThat(load().rules()).extracting(EditRule::id).containsExactly("age");
        }

        @Test
        void rejectsARuleThatDoesNotBehaveAsItsExamplesSay() throws IOException {
            rules("""
                      - id: r
                        severity: ERROR
                        message: m
                        fields: [ssn]
                        required: true
                        when: { field: status, equals: married }
                        examples:
                          pass: [ { status: married } ]
                          fail: [ { status: married, ssn: '1' } ]
                          notApplicable: [ { status: married, ssn: '1' } ]
                    """);

            assertProblems("Rule 'r' example pass[0] {status=married} should be PASSED but was FAILED",
                    "Rule 'r' example fail[0] {ssn=1, status=married} should be FAILED but was PASSED",
                    "Rule 'r' example notApplicable[0] {ssn=1, status=married} should be NOT_APPLICABLE");
        }

        @Test
        void rejectsExamplesWithUnknownFieldsOrValuesThatDoNotFit() throws IOException {
            rules("""
                      - id: r
                        severity: ERROR
                        message: m
                        fields: [ssn]
                        required: true
                        examples:
                          pass: [ { middleName: x } ]
                          fail: [ { count: lots } ]
                    """);

            assertProblems("Rule 'r' example pass[0] middleName is not a known field",
                    "Rule 'r' example fail[0] count must be a whole number");
        }

        @Test
        void requiresAPassingAndAFailingExampleForEveryRule() throws IOException {
            rules("""
                      - { id: none, severity: ERROR, message: m, fields: [ssn], required: true }
                      - { id: fail-only, severity: ERROR, message: m, fields: [ssn], required: true, examples: { pass: [], fail: [ {} ] } }
                    """);

            assertProblems("rules[0].examples must not be null", "rules[1].examples.pass must not be empty");
        }

        @Test
        void requiresTheExamplesDate() throws IOException {
            write("rules.yml", "rules:\n" + ssnRule("r", "required: true"));

            assertProblems("examplesAsOf must not be null");
        }

        @Test
        void rejectsUnparseableYaml() throws IOException {
            write("rules.yml", "rules: [ { id: x");

            assertProblems("but got <stream end>");
        }

        @Test
        void rejectsUnknownPropertiesSoTyposAreNotSilentlyIgnored() throws IOException {
            rules(ssnRule("r", "requird: true"));

            assertProblems("Unrecognized field \"requird\"");
        }

        @Test
        void rejectsChecksThatAreNeitherBuiltInNorDefined() throws IOException {
            rules(ssnRule("r", "check: { type: isPrime }"));

            assertProblems("Rule 'r' uses check 'isPrime', which is not built in or defined in checks.yml");
        }

        @Test
        void rejectsInvalidRegularExpressions() throws IOException {
            rules(ssnRule("r", "check: { type: matches, regex: '[unclosed' }"));

            assertProblems("Unclosed character class");
        }

        @Test
        void rejectsAnEmptyRuleList() throws IOException {
            write("rules.yml", "examplesAsOf: 2026-11-01\nrules: []");

            assertProblems("rules must not be empty");
        }

        @Test
        void reportsEveryMissingRequiredPropertyAtOnce() throws IOException {
            rules("""
                      - id: incomplete
                        required: true
                        examples: { pass: [ {} ], fail: [ {} ] }
                    """);

            assertProblems("rules[0].severity must not be null",
                    "rules[0].message must not be blank",
                    "rules[0].fields must not be empty");
        }

        @Test
        void rejectsAnInvalidCheck() throws IOException {
            rules(ssnRule("r", "check: { type: oneOf, values: [] }"));

            assertProblems("rules[0].check.values must not be empty");
        }

        @Test
        void rejectsARuleThatChecksNothing() throws IOException {
            rules(ssnRule("r", ""));

            assertProblems("Rule 'r' needs 'required: true', a 'check', or both");
        }

        @Test
        void rejectsUnknownFieldsWhereverTheyAreUsed() throws IOException {
            rules("""
                      - id: checked
                        severity: ERROR
                        message: m
                        fields: [middleName]
                        check: { type: atMost, field: pets }
                        when: { field: favoriteColor, equals: blue }
                        examples: { pass: [ {} ], fail: [ {} ] }
                      - { id: required, severity: ERROR, message: m, fields: [nickname], required: true, examples: { pass: [ {} ], fail: [ {} ] } }
                    """);

            assertProblems("Rule 'checked' uses unknown field 'middleName'",
                    "Rule 'checked' uses unknown field 'pets'",
                    "Rule 'checked' uses unknown field 'favoriteColor'",
                    "Rule 'required' uses unknown field 'nickname'");
        }

        @Test
        void rejectsChecksOnTheWrongTypeOfFieldAndMixedCombinations() throws IOException {
            rules(ssnRule("age", "check: { type: minAge, years: 14 }")
                    + ssnRule("mixed", "check: { type: allOf, checks: [ { type: min, value: 0 }, { type: minAge, years: 1 } ] }"));

            assertProblems("Rule 'age' applies a LocalDate check to 'ssn', which is a String",
                    "Rule 'mixed' combines checks for different types of value");
        }

        @Test
        void rejectsDuplicateIds() throws IOException {
            rules(ssnRule("r", "required: true") + ssnRule("r", "required: true"));

            assertProblems("Rule 'r' is defined more than once");
        }
    }

    private Ruleset load() {
        return loader.load(new FileSystemResource(directory.toString() + "/"));
    }

    /** A rule on ssn with one extra YAML line and examples that suit a required ssn. */
    private static String ssnRule(String id, String extraLine) {
        return """
                  - id: %s
                    severity: ERROR
                    message: m
                    fields: [ssn]
                    examples: { pass: [ { ssn: '123456789' } ], fail: [ {} ] }
                    %s
                """.formatted(id, extraLine);
    }

    private void rules(String rules) throws IOException {
        write("rules.yml", "examplesAsOf: 2026-11-01\nrules:\n" + rules);
    }

    private void write(String file, String content) throws IOException {
        Files.writeString(directory.resolve(file), content);
    }

    private void assertProblems(String... expectedProblems) {
        assertThatThrownBy(this::load)
                .isInstanceOfSatisfying(InvalidConfigException.class, ex -> {
                    for (String expected : expectedProblems) {
                        assertThat(ex.problems()).anySatisfy(problem -> assertThat(problem).contains(expected));
                    }
                });
    }
}
