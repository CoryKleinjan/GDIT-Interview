# FAFSA Edit Rule Processor

A Spring Boot service that applies FAFSA "edit" rules to an application and reports, rule by rule, what passed, what failed, why, and how to fix it.

- Java 21, Spring Boot 3.5, Maven (wrapper included)
- 8 rules: the 7 required edits plus one warning-level plausibility check
- 84 tests, **100% line and branch coverage**, enforced by the build (`mvn verify` fails below 100%)

Design reasoning is in [DECISIONS.md](DECISIONS.md).

## Build, test, run

Prerequisite: JDK 21. Maven is optional, because `./mvnw` downloads the right version on first use.

```bash
./mvnw verify            # compile, run all tests, enforce 100% line + branch coverage
./mvnw spring-boot:run   # start on http://localhost:8080
```

The coverage report is written to `target/site/jacoco/index.html`.

To run the packaged jar:

```bash
./mvnw package
java -jar target/fafsa-edit-processor-0.1.0.jar
```

## API

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/v1/applications/validate` | one application | `ValidationResult` |
| POST | `/api/v1/applications/validate/batch` | `{"applications": [ ... ]}` (1 to 1000) | `{"results": [ ... ]}` in request order |
| GET | `/actuator/health` | | service health |
| GET | `/actuator/metrics/fafsa.applications.validated` | | validations by `status` |
| GET | `/actuator/metrics/fafsa.edits.failed` | | failures by `rule` and `severity` |

An application that fails edits still returns **200**, because the edit results are the answer. **400** is only for requests that can't be evaluated at all: unreadable JSON, wrong value types, unknown enum values, a missing `dependencyStatus` or `maritalStatus`, or an empty or oversized batch. Enum values are case-insensitive (`"dependent"` and `"DEPENDENT"` both work).

### Example usage

The spec's two sample payloads are in `src/test/resources/samples/`, and the API tests use the same files.

**Valid application**

```bash
curl -s -X POST http://localhost:8080/api/v1/applications/validate \
  -H 'Content-Type: application/json' \
  -d @src/test/resources/samples/valid-application.json
```

```json
{
  "status": "VALID",
  "results": [
    { "ruleId": "student-age", "outcome": "PASSED", "severity": "ERROR", "fields": ["studentInfo.dateOfBirth"] },
    { "ruleId": "ssn-format", "outcome": "PASSED", "severity": "ERROR", "fields": ["studentInfo.ssn"] },
    "... one entry per rule ...",
    { "ruleId": "married-spouse-info", "outcome": "NOT_APPLICABLE", "severity": "ERROR", "fields": ["spouseInfo.firstName", "spouseInfo.lastName", "spouseInfo.ssn"] },
    { "ruleId": "student-age-plausible", "outcome": "PASSED", "severity": "WARNING", "fields": ["studentInfo.dateOfBirth"] }
  ]
}
```

**Invalid application** (all seven violations from the spec)

```bash
curl -s -X POST http://localhost:8080/api/v1/applications/validate \
  -H 'Content-Type: application/json' \
  -d @src/test/resources/samples/invalid-application.json
```

```json
{
  "status": "INVALID",
  "results": [
    {
      "ruleId": "student-age",
      "outcome": "FAILED",
      "severity": "ERROR",
      "message": "Student must be at least 14 years old. Check that the date of birth is correct (YYYY-MM-DD).",
      "fields": ["studentInfo.dateOfBirth"]
    },
    {
      "ruleId": "ssn-format",
      "outcome": "FAILED",
      "severity": "ERROR",
      "message": "SSN must be exactly 9 digits with no dashes or spaces, for example 123456789.",
      "fields": ["studentInfo.ssn"]
    },
    "... dependent-parent-income, non-negative-income, household-college-count, state-code, married-spouse-info: FAILED ...",
    { "ruleId": "student-age-plausible", "outcome": "PASSED", "severity": "WARNING", "fields": ["studentInfo.dateOfBirth"] }
  ]
}
```

**Batch**

```bash
curl -s -X POST http://localhost:8080/api/v1/applications/validate/batch \
  -H 'Content-Type: application/json' \
  -d @src/test/resources/samples/batch.json
# {"results":[{"status":"VALID",...},{"status":"INVALID",...}]}
```

**Unusable request** (RFC 9457 problem detail)

```bash
curl -s -X POST http://localhost:8080/api/v1/applications/validate \
  -H 'Content-Type: application/json' -d '{"stateOfResidence": "CA"}'
```

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/v1/applications/validate",
  "errors": ["dependencyStatus: must not be null", "maritalStatus: must not be null"]
}
```

### Response fields

| Field | Meaning |
|---|---|
| `status` | `VALID`, `VALID_WITH_WARNINGS` (only WARNING rules failed) or `INVALID` (any ERROR rule failed) |
| `results[].ruleId` | Stable rule id, also its key in `application.yml` |
| `results[].outcome` | `PASSED`, `FAILED`, or `NOT_APPLICABLE` (a conditional rule whose condition didn't hold) |
| `results[].severity` | `ERROR`, `WARNING` or `INFO` |
| `results[].message` | Only on failures: what's wrong and how to fix it |
| `results[].fields` | JSON paths of the fields the rule checks |

## Rules

| Id | Rule | Severity | Applies when |
|---|---|---|---|
| `student-age` | Student is at least 14 (date of birth required) | ERROR | always |
| `ssn-format` | SSN is exactly 9 digits | ERROR | always |
| `dependent-parent-income` | Parent income is present | ERROR | dependent |
| `non-negative-income` | Student and parent income are not negative | ERROR | always |
| `household-college-count` | Both counts are present, and college ≤ household | ERROR | always |
| `state-code` | State is a valid US state, DC or territory code | ERROR | always |
| `married-spouse-info` | Spouse first name, last name and SSN are present | ERROR | married |
| `student-age-plausible` | Student is not older than 120 | WARNING | date of birth present |

Severity, message and an `enabled` flag for each rule are set in [`application.yml`](src/main/resources/application.yml) under `fafsa.edits.rules`. As with any Spring property, they can be overridden at deploy time without a rebuild, for example `--fafsa.edits.rules.ssn-format.severity=WARNING`.

### Adding a rule

1. Add a `@Bean` method to [`FafsaEditRules`](src/main/java/com/gdit/fafsa/rules/FafsaEditRules.java) built from `EditRule.of(id, check, fields...)`, plus `.onlyWhen(condition)` if it's conditional.
2. Add its `severity` and `message` under `fafsa.edits.rules.<id>` in `application.yml`.
3. Add a `@Nested` test class to `FafsaEditRulesTest`.

The service refuses to start if a rule has no configuration, or configuration names a rule that doesn't exist.

## Project layout

```
src/main/java/com/gdit/fafsa/
  model/   FafsaApplication: request records (null sections become empty)
  engine/  EditRule, EditEngine, results, severity, rule settings (generic, nothing FAFSA-specific)
  rules/   FafsaEditRules (one bean per rule) and Checks (shared null-safe helpers)
  api/     controller, batch request/response, 400 handling
src/test/java/...   tests mirror the packages; ApplicationBuilder makes test data
```

Logs are structured JSON (Elastic Common Schema). Each validation logs its status and the ids of failed rules, and never names, SSNs or other submitted values.
