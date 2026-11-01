# FAFSA Edit Rule Processor

A Spring Boot service that applies FAFSA "edit" rules to an application and reports, rule by rule, what passed, what failed, why, and how to fix it.

- Rules live in a YAML file, **not in code**. You can add, change or remove a rule by editing the file, with no rebuild, redeploy or restart.
- Each rule carries its own **examples** (applications it must pass, fail, or not apply to). They're checked every time the file loads, so a rule that doesn't behave as described never goes live.
- The shipped rule set has the 7 required edits plus 4 more: marital and dependency status, a married student's household size, and a warning-level age plausibility check.
- Java 21, Spring Boot 3.5, Maven (wrapper included).
- 98 JUnit tests plus 55 rule examples, **100% line and branch coverage**, enforced by the build (`mvn verify` fails below 100%).

Design reasoning is in [DECISIONS.md](DECISIONS.md).

## Build, test, run

Prerequisite: JDK 21. Maven is optional, because `./mvnw` downloads the right version on first use.

```bash
./mvnw verify            # compile, run all tests, enforce 100% line + branch coverage
./mvnw spring-boot:run   # start on http://localhost:8080 with the bundled rules
```

The coverage report is written to `target/site/jacoco/index.html`.

To run with an external rule file you can edit live:

```bash
./mvnw package
cp src/main/resources/rules.yml /tmp/rules.yml
java -jar target/fafsa-edit-processor-0.1.0.jar --fafsa.edits.rules-location=file:/tmp/rules.yml
# edit /tmp/rules.yml; within 10 seconds the change is live (check GET /api/v1/rules)
```

## API

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/v1/applications/validate` | one application | `ValidationResult` |
| POST | `/api/v1/applications/validate/batch` | `{"applications": [ ... ]}` (1 to 1000) | `{"results": [ ... ]}` in request order, validated in parallel |
| GET | `/api/v1/rules` | | the rules that are live right now, in priority order |
| GET | `/actuator/health` | | service health |
| GET | `/actuator/metrics/fafsa.applications.validated` | | validations by `status` |
| GET | `/actuator/metrics/fafsa.edits.failed` | | failures by `rule` and `severity` |
| GET | `/actuator/metrics/fafsa.rules.reloads` | | rule file reloads by `result` (`success` or `rejected`) |
| GET | `/actuator/metrics/executor.active?tag=name:fafsa.batch` | | batch thread pool usage (also `executor.queued`, `executor.pool.size`) |

An application that fails edits still returns **200**, because the edit results are the answer. **400** is only for requests that can't be read at all: malformed JSON, wrong value types (`"numberInHousehold": "four"`), unknown enum values, or an empty or oversized batch. Enum values are case-insensitive (`"dependent"` and `"DEPENDENT"` both work).

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
    { "ruleId": "marital-status", "outcome": "PASSED", "severity": "ERROR", "fields": ["maritalStatus"] },
    { "ruleId": "dependency-status", "outcome": "PASSED", "severity": "ERROR", "fields": ["dependencyStatus"] },
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
    { "ruleId": "marital-status", "outcome": "PASSED", "severity": "ERROR", "fields": ["maritalStatus"] },
    { "ruleId": "dependency-status", "outcome": "PASSED", "severity": "ERROR", "fields": ["dependencyStatus"] },
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
    "... dependent-parent-income, non-negative-income, married-spouse-info, household-college-count, state-code: FAILED ...",
    { "ruleId": "married-household-includes-spouse", "outcome": "PASSED", "severity": "ERROR", "fields": ["household.numberInHousehold"] },
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

**Unreadable request** (RFC 9457 problem detail)

```bash
curl -s -X POST http://localhost:8080/api/v1/applications/validate \
  -H 'Content-Type: application/json' -d '{"household": {"numberInHousehold": "four"}}'
```

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid value for field 'household.numberInHousehold'.",
  "instance": "/api/v1/applications/validate"
}
```

### Response fields

| Field | Meaning |
|---|---|
| `status` | `VALID`, `VALID_WITH_WARNINGS` (only WARNING rules failed) or `INVALID` (any ERROR rule failed) |
| `results[].ruleId` | Stable rule id from the rule file |
| `results[].outcome` | `PASSED`, `FAILED`, or `NOT_APPLICABLE` (a conditional rule whose `when` didn't hold) |
| `results[].severity` | `ERROR`, `WARNING` or `INFO` |
| `results[].message` | Only on failures: what's wrong and how to fix it |
| `results[].fields` | JSON paths of the fields the rule checks |

## Rules

The rules are in [`src/main/resources/rules.yml`](src/main/resources/rules.yml). They run, and are reported, **top to bottom: file order is priority**. Every rule always runs, so an applicant sees every problem in one response.

| # | Id | Rule | Severity | Applies when |
|---|---|---|---|---|
| 1 | `marital-status` | Marital status is present | ERROR | always |
| 2 | `dependency-status` | Dependency status is present | ERROR | always |
| 3 | `student-age` | Date of birth is present and the student is at least 14 | ERROR | always |
| 4 | `ssn-format` | SSN is exactly 9 digits | ERROR | always |
| 5 | `dependent-parent-income` | Parent income is present | ERROR | dependent |
| 6 | `non-negative-income` | Student and parent income are not negative | ERROR | always |
| 7 | `married-spouse-info` | Spouse first name, last name and SSN are present | ERROR | married |
| 8 | `household-college-count` | Both counts are present, and college ≤ household | ERROR | always |
| 9 | `married-household-includes-spouse` | Number in household is at least 2 | ERROR | married |
| 10 | `state-code` | State is a valid US state, DC or territory code | ERROR | always |
| 11 | `student-age-plausible` | Student is not older than 120 | WARNING | always |

### Writing a rule

```yaml
- id: married-household-includes-spouse        # stable id, reported in results
  severity: ERROR                              # ERROR | WARNING | INFO
  message: A married student's household must include the student and spouse, so number in household must be at least 2.
  fields: [household.numberInHousehold]        # every listed field must pass
  required: true                               # missing (null or blank) fails; false = missing passes
  check: { type: min, value: 2 }               # optional test for present values
  when: { field: maritalStatus, equals: married }   # optional; otherwise the rule always applies
  # enabled: false                             # optional switch, default true
  examples:                                    # the rule's tests: { field: value } partial applications
    pass: [ { maritalStatus: married, household.numberInHousehold: 2 } ]
    fail: [ { maritalStatus: married, household.numberInHousehold: 1 }, { maritalStatus: married } ]
    notApplicable: [ { maritalStatus: single, household.numberInHousehold: 1 } ]
```

**Examples are required.** Every rule needs at least one `pass` and one `fail` example (`notApplicable` is optional). Each example is a partial application: fields you leave out are empty. The loader evaluates every example as if today were the file's `examplesAsOf` date, so age examples never go stale. If any rule has no examples, or a rule's result differs from its examples, the whole file is rejected. Adding a rule therefore needs only its YAML entry, tests included.

Available checks:

| `type` | Parameters | Works on | Passes when |
|---|---|---|---|
| `matches` | `regex` | text | the whole value matches |
| `oneOf` | `values` | text | the value is in the list (case-sensitive) |
| `min` | `value` | numbers | value ≥ `value` |
| `atMost` | `field` | numbers | value ≤ the other field (which must be present) |
| `minAge` | `years` | dates | age today ≥ `years` |
| `maxAge` | `years` | dates | age today ≤ `years` |

Fields a rule can reference: `studentInfo.firstName`, `studentInfo.lastName`, `studentInfo.ssn`, `studentInfo.dateOfBirth`, `dependencyStatus`, `maritalStatus`, `spouseInfo.firstName`, `spouseInfo.lastName`, `spouseInfo.ssn`, `household.numberInHousehold`, `household.numberInCollege`, `income.studentIncome`, `income.parentIncome`, `stateOfResidence`.

### Changing rules without a deployment

1. Set `fafsa.edits.rules-location` to a file outside the jar, for example `file:/etc/fafsa/rules.yml` (or the environment variable `FAFSA_EDITS_RULES_LOCATION`).
2. Edit that file. It's checked every `fafsa.edits.reload-interval` (default 10s).
3. If the whole file is valid, the new rules go live and `GET /api/v1/rules` shows them. If anything is wrong (a YAML error, a typo in a property name, an unknown field or check type, a check on the wrong type of field, a duplicate id, or a rule that fails its own examples), the file is **rejected as a whole**. The current rules stay live, and the problems are logged and counted in `fafsa.rules.reloads{result=rejected}`.

At startup, an invalid rule file stops the service, so it can never start with no rules or partial rules.

Only a genuinely new *kind* of check, or a new field in the application, needs code: a record in `Check.java`, or an entry in `Fields.java`.

## Project layout

```
src/main/java/com/gdit/fafsa/
  model/   FafsaApplication: request records (null sections become empty)
  engine/  EditRule, EditEngine, results, severity (generic, nothing FAFSA-specific)
  rules/   rule file format (RuleDefinition, Check, Condition, Examples), Fields registry, RuleLoader, RuleFileWatcher
  api/     controllers, batch thread pool, batch request/response, 400 handling
src/main/resources/rules.yml   the shipped rule set
src/test/java/...              tests mirror the packages; ApplicationBuilder makes test data
```

Batch validation runs on a dedicated thread pool sized by `fafsa.edits.batch-threads` (default `0`, meaning one thread per processor).

Logs are structured JSON (Elastic Common Schema). Each validation logs its status and the ids of failed rules, and never names, SSNs or other submitted values.
