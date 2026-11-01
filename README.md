# FAFSA Edit Rule Processor

A Spring Boot service that applies FAFSA "edit" rules to an application and reports, rule by rule, what passed, what failed, why, and how to fix it.

- The edit configuration lives in three YAML files, **not in code**: `fields.yml` (the application's fields and their types), `checks.yml` (named, reusable checks) and `rules.yml` (the rules). You can add, change or remove a field, check or rule by editing a file, with no rebuild, redeploy or restart.
- Every check and rule carries its own **examples** (values or applications it must pass, fail, or not apply to). They're verified every time the files load, so a check or rule that doesn't behave as described never goes live.
- The shipped rule set has the 7 required edits plus 4 more: marital and dependency status, a married student's household size, and a warning-level age plausibility check.
- Java 21, Spring Boot 3.5, Maven (wrapper included).
- 162 JUnit tests plus 74 YAML examples (55 rule, 19 check), **100% line and branch coverage**, enforced by the build (`mvn verify` fails below 100%).

Design reasoning is in [DECISIONS.md](DECISIONS.md).

## Build, test, run

Prerequisite: JDK 21. Maven is optional, because `./mvnw` downloads the right version on first use.

```bash
./mvnw verify            # compile, run all tests, enforce 100% line + branch coverage
./mvnw spring-boot:run   # start on http://localhost:8080 with the bundled configuration
```

Then open **http://localhost:8080** for the manual testing page (see [Manual testing UI](#manual-testing-ui)).

The coverage report is written to `target/site/jacoco/index.html`.

To run with an external configuration folder you can edit live:

```bash
./mvnw package
cp -r src/main/resources/edits /tmp/edits
java -jar target/fafsa-edit-processor-0.1.0.jar --fafsa.edits.config-location=file:/tmp/edits/
# edit any file in /tmp/edits; within 10 seconds the change is live (check GET /api/v1/rules or /api/v1/fields)
```

## API

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/v1/applications/validate` | one application | `ValidationResult` |
| POST | `/api/v1/applications/validate/batch` | `{"applications": [ ... ]}` (1 to 1000) | `{"results": [ ... ]}` in request order, validated in parallel |
| GET | `/api/v1/rules` | | the rules that are live right now, in priority order |
| GET | `/api/v1/fields` | | the application fields that are live right now, with their types |
| GET | `/actuator/health` | | service health |
| GET | `/actuator/metrics/fafsa.applications.validated` | | validations by `status` |
| GET | `/actuator/metrics/fafsa.edits.failed` | | failures by `rule` and `severity` |
| GET | `/actuator/metrics/fafsa.config.reloads` | | configuration reloads by `result` (`success` or `rejected`) |
| GET | `/actuator/metrics/executor.active?tag=name:fafsa.batch` | | batch thread pool usage (also `executor.queued`, `executor.pool.size`) |

An application that fails edits still returns **200**, because the edit results are the answer. **400** is only for requests that can't be read at all: malformed JSON, a body that isn't a JSON object, a value that doesn't fit its field's type in `fields.yml` (`"numberInHousehold": "four"`, a choice that isn't listed), or an empty or oversized batch. Every such problem in the request is listed at once. Choice values are case-insensitive (`"dependent"` and `"DEPENDENT"` both work). Fields that aren't in `fields.yml` are ignored.

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
    { "ruleId": "marital-status", "outcome": "PASSED", "fields": ["maritalStatus"] },
    { "ruleId": "dependency-status", "outcome": "PASSED", "fields": ["dependencyStatus"] },
    "... one entry per rule ...",
    { "ruleId": "married-spouse-info", "outcome": "NOT_APPLICABLE", "fields": ["spouseInfo.firstName", "spouseInfo.lastName", "spouseInfo.ssn"] },
    { "ruleId": "student-age-plausible", "outcome": "PASSED", "fields": ["studentInfo.dateOfBirth"] }
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
    { "ruleId": "marital-status", "outcome": "PASSED", "fields": ["maritalStatus"] },
    { "ruleId": "dependency-status", "outcome": "PASSED", "fields": ["dependencyStatus"] },
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
    { "ruleId": "married-household-includes-spouse", "outcome": "PASSED", "fields": ["household.numberInHousehold"] },
    { "ruleId": "student-age-plausible", "outcome": "PASSED", "fields": ["studentInfo.dateOfBirth"] }
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
  -H 'Content-Type: application/json' \
  -d '{"household": {"numberInHousehold": "four"}, "dependencyStatus": "sometimes"}'
```

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Some fields have values that do not fit their type.",
  "instance": "/api/v1/applications/validate",
  "errors": [
    "dependencyStatus must be one of [dependent, independent]",
    "household.numberInHousehold must be a whole number"
  ]
}
```

### Response fields

| Field | Meaning |
|---|---|
| `status` | `VALID`, `VALID_WITH_WARNINGS` (only WARNING rules failed) or `INVALID` (any ERROR rule failed) |
| `results[].ruleId` | Stable rule id from the rule file |
| `results[].outcome` | `PASSED`, `FAILED`, or `NOT_APPLICABLE` (a conditional rule whose `when` didn't hold) |
| `results[].severity` | Only on failures: `ERROR`, `WARNING` or `INFO`. A rule's configured severity is always visible at `GET /api/v1/rules`. |
| `results[].message` | Only on failures: what's wrong and how to fix it |
| `results[].fields` | JSON paths of the fields the rule checks |

## Configuration

The configuration is one folder, [`src/main/resources/edits/`](src/main/resources/edits/), with three files that are loaded, validated and reloaded together:

| File | Holds | Can use |
|---|---|---|
| [`fields.yml`](src/main/resources/edits/fields.yml) | every field a request can carry, by JSON path, and its type | |
| [`checks.yml`](src/main/resources/edits/checks.yml) | named checks, built from the built-in checks | fields |
| [`rules.yml`](src/main/resources/edits/rules.yml) | the rules, in priority order | fields, built-in and named checks |

### Fields

```yaml
fields:
  - { path: studentInfo.dateOfBirth, type: date }
  - { path: maritalStatus, type: choice, values: [single, married] }
  - { path: household.numberInHousehold, type: integer }
```

Types are `text`, `integer`, `number`, `date` (YYYY-MM-DD) and `choice` (needs `values`, matched case-insensitively). Requests are read against this list, so a field added here can be sent, used by checks and rules, and appears in the testing page's form, all without a deployment. A field can't be both a value and a section (`household` and `household.size`).

### Rules

They run, and are reported, They run, and are reported, **top to bottom: file order is priority**. Every rule always runs, so an applicant sees every problem in one response.

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

A rule can only use fields in `fields.yml`, and its checks must fit those fields' types (an age check on a date, a minimum on a number).

**Examples are required.** Every rule needs at least one `pass` and one `fail` example (`notApplicable` is optional). Each example is a partial application: fields you leave out are empty. The loader evaluates every example as if today were the file's `examplesAsOf` date, so age examples never go stale. If any rule has no examples, or a rule's result differs from its examples, the whole file is rejected. Adding a rule therefore needs only its YAML entry, tests included.

### Checks

A rule's `check` is a built-in check, a combination, or a named check from `checks.yml`.

| `type` | Parameters | Works on | Passes when |
|---|---|---|---|
| `matches` | `regex` | text | the whole value matches |
| `oneOf` | `values` | text | the value is in the list (case-sensitive) |
| `min` | `value` | numbers | value ≥ `value` |
| `atMost` | `field` | numbers | value ≤ the other field (which must be present) |
| `minAge` | `years` | dates | age today ≥ `years` |
| `maxAge` | `years` | dates | age today ≤ `years` |
| `allOf` | `checks` | the type they share | every listed check passes |
| `anyOf` | `checks` | the type they share | at least one listed check passes |
| `not` | `check` | the inner check's type | the inner check fails |

A named check gives a check a name, a description and value examples, so rules can reuse it:

```yaml
examplesAsOf: 2026-11-01
checks:
  - name: nineDigits
    description: Exactly nine digits, with no dashes or spaces.
    check: { type: matches, regex: '\d{9}' }
    examples:
      field: studentInfo.ssn             # the examples are read as this field's type
      pass: ['123456789']
      fail: ['123-45-6789', '']
```

A rule then uses `check: { type: nineDigits }`. A named check can use built-in checks and named checks defined above it, and can't reuse a built-in name. The shipped file defines `nineDigits`, `nonNegative` and `usStateOrTerritoryCode`.

### Changing the configuration without a deployment

1. Set `fafsa.edits.config-location` to a folder outside the jar, for example `file:/etc/fafsa/edits/` (or the environment variable `FAFSA_EDITS_CONFIG_LOCATION`). Keep the trailing slash.
2. Edit any of the three files. They're checked every `fafsa.edits.reload-interval` (default 10s).
3. If all three files are valid together, the change goes live and `GET /api/v1/rules` and `GET /api/v1/fields` show it. If anything is wrong (a YAML error, a typo in a property name, an unknown field or check, a check on the wrong type of field, a duplicate, a field a rule still uses being removed, or a check or rule that fails its own examples), the change is **rejected as a whole**. The current configuration stays live, and the problems are logged and counted in `fafsa.config.reloads{result=rejected}`.

At startup, an invalid configuration stops the service, so it can never start with no rules or partial rules.

Only a genuinely new *built-in* check, one that can't be written by combining the existing ones, needs code: a record in `Check.java`.

## Manual testing UI

`http://localhost:8080` serves a single page for trying the service by hand. It's plain HTML and JavaScript in `src/main/resources/static/index.html`, with no build step or extra dependencies, and it calls the same public endpoints as any client.

- **Input:** a form built from `GET /api/v1/fields`, so it always matches `fields.yml`, or a raw JSON tab (send malformed JSON to see the 400 response). Buttons load the spec's valid and invalid samples.
- **Validate:** shows the overall status and a colour-coded row per rule: green for passed, red for a failed ERROR, amber for a failed WARNING, grey for not applicable. Fields named by failed rules are outlined in the form.
- **Live rules:** the rules currently loaded, in priority order. The list and the form refresh every 10 seconds and when you return to the tab. Run with an external configuration folder (see [Changing rules without a deployment](#changing-rules-without-a-deployment)), edit it, and you'll see the change here and in the next validation, with no restart.

## Project layout

```
src/main/java/com/gdit/fafsa/
  engine/  Schema (reads JSON by field type), Application, EditRule, EditEngine, results (generic, nothing FAFSA-specific)
  config/  file formats (FieldFile, CheckFile, RuleFile, Check, ...), ConfigLoader, ConfigWatcher
  api/     controllers, batch thread pool, batch request/response, 400 handling
src/main/resources/edits/      the shipped configuration: fields.yml, checks.yml, rules.yml
src/main/resources/static/     the manual testing page
src/test/java/...              tests mirror the packages
```

Batch validation runs on a dedicated thread pool sized by `fafsa.edits.batch-threads` (default `0`, meaning one thread per processor).

Logs are structured JSON (Elastic Common Schema). Each validation logs its status and the ids of failed rules, and never names, SSNs or other submitted values.
