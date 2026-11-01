# Design decisions

## Goals I optimized for

1. **Correct and consistent.** Every enabled rule runs on every application, and the build enforces 100% line and branch coverage.
2. **Easy to change.** A rule's logic lives in one small method, and its severity, message and on/off switch live in config.
3. **Readable and concise.** Records instead of boilerplate, rules that read like the spec, and shared helpers instead of repeated null checks.

## Stack

**Java 21 and Spring Boot 3.5**, because that's the team's stack. Records and pattern matching keep the code short. Spring gives validation, problem details, Actuator and Micrometer without extra libraries. Maven runs through the wrapper, and the build has no other dependencies.

## The open questions

### Rule representation: code for logic, YAML for policy

Each rule is an `EditRule` record: an id, the fields it checks, an `appliesTo` predicate and a `check` predicate. The FAFSA rules are `@Bean` methods in one class, `FafsaEditRules`, so the whole rulebook can be read top to bottom in one file:

```java
@Bean
public EditRule dependentParentIncome() {
    return EditRule.of("dependent-parent-income",
                    application -> application.income().parentIncome() != null,
                    "income.parentIncome")
            .onlyWhen(FafsaApplication::isDependent);
}
```

What changes most often during an aid year (severity, wording, turning a rule off) is policy, not logic. That lives in `application.yml` under `fafsa.edits.rules.<id>`, so it can change without a code change, or be overridden per environment at deploy time.

I considered and rejected these alternatives:

- **A DSL or expression language (SpEL, JSON rules).** It would let non-developers edit logic, but it moves type checking and testability from compile time to run time. With seven rules that need conditionals, date math and lookups, plain Java predicates are safer and easier to test. If analysts needed to author rules, I'd revisit this.
- **A database.** It adds a runtime dependency and makes the logic unreviewable in a pull request.
- **One class per rule.** Each class would be mostly boilerplate around a one-line check. Bean methods give the same isolation and testability with far less code.

**Guarding against drift.** `EditEngine` refuses to start if any rule has no config entry, if config names a rule that doesn't exist (a typo), or if two rules share an id. A misconfigured deploy fails at startup instead of silently skipping an edit.

### Rule priority and order

The rules are independent: none reads another's result. So order doesn't change the outcome, and the engine can apply them in any order (or in parallel). Results are reported in a deterministic order, the order rules are declared in `FafsaEditRules` (which follows the spec's numbering). That makes responses stable for clients and diffs.

I first used YAML order, but found that a command-line override (`--fafsa.edits.rules.ssn-format.severity=WARNING`) moves that rule to the top, because Spring merges map keys from higher-priority sources first. Declaration order doesn't have that problem (see commit `refactor: report results in rule registration order`).

If a future rule genuinely depends on another (for example, only check SSN against a registry once its format is valid), I'd express that as an explicit `onlyWhen` condition on that rule rather than relying on execution order.

### Error handling: collect all, not fail fast

Every rule runs and every result is returned, including passes and `NOT_APPLICABLE`. An applicant fixing a form wants the whole list at once, not one error per round trip. Rules are cheap, so there's no performance reason to stop early.

There are two kinds of "bad input", and they're kept separate:

| | Response | Examples |
|---|---|---|
| **Edit failures:** the application is readable but wrong | **200** with `status: INVALID` and the failed rules | bad SSN, negative income, missing parent income |
| **Unusable requests:** the engine can't evaluate it | **400** problem detail (RFC 9457) | malformed JSON, `"numberInHousehold": "four"`, missing `dependencyStatus` or `maritalStatus`, empty batch |

`dependencyStatus` and `maritalStatus` are the only required fields at the schema level, because they decide which conditional rules apply. Without them, rules 3 and 7 can't be applied consistently. Every other missing value is reported by the rule that needs it, with a useful message. For example, a missing date of birth fails `student-age`, and a missing SSN fails `ssn-format`. Missing whole sections (`household`, `income`, ...) become empty records, so rules never need null checks on sections.

400 messages name the offending field (`Invalid value for field 'applications[0].household.numberInHousehold'.`) but never echo the submitted value, because values can be SSNs.

### Rule conflicts

Because each rule checks one fact and none depends on another's result, two rules can fail together but they can't contradict each other. For example, the invalid sample fails seven rules independently. Two cases are handled deliberately:

- **Overlapping checks** are avoided by giving each concern one owner. "Is income present?" belongs to `dependent-parent-income`, and "Is income negative?" belongs to `non-negative-income`. So `isNonNegative(null)` is true, and a missing amount is never reported as a negative one.
- **Severity conflicts** are resolved by the roll-up, where the worst failure wins: any ERROR → `INVALID`, otherwise any WARNING → `VALID_WITH_WARNINGS`, otherwise `VALID`.

If real conflicts appeared (for example, two rules wanting different corrections to the same field), I'd add a post-processing step that groups results by field.

### Performance

- Rules are stateless pure functions over an immutable record, so one engine instance is shared safely across request threads.
- Per application, the work is 8 predicate calls with no I/O. The state code check is a `Set` lookup, and the SSN pattern is compiled once.
- The batch endpoint validates applications with `parallelStream()`, and `toList()` keeps the results in request order. Batches are capped at 1000 to bound memory and request time.
- Rule lookup and filtering by `enabled` happen once at startup, not per request.

At FAFSA scale (millions of applications, peaking around October 1), I'd put the engine behind a queue (Kafka or SQS) and scale consumers horizontally, rather than grow the HTTP batch size. The engine class wouldn't change, because it has no HTTP dependency.

### Extensibility

Adding a rule takes three small steps (see [README](README.md#adding-a-rule)): a bean method, a YAML entry and a nested test class. Shared helpers in `Checks` (`matches`, `isNonNegative`, `isOneOf`, `allHaveText`, `ageInYears`) make most new rules one or two lines. For example, validating the spouse's SSN format would be:

```java
@Bean
public EditRule spouseSsnFormat() {
    return EditRule.of("spouse-ssn-format",
                    application -> matches(application.spouseInfo().ssn(), NINE_DIGITS), "spouseInfo.ssn")
            .onlyWhen(FafsaApplication::isMarried);
}
```

The `engine` package knows nothing about FAFSA, so it would also work for another form.

### Severity levels

| Severity | Meaning | Effect on status |
|---|---|---|
| `ERROR` | The application can't be processed until this is fixed | → `INVALID` |
| `WARNING` | Suspicious but processable, so the applicant should confirm it | → `VALID_WITH_WARNINGS` |
| `INFO` | Informational only | none |

All seven required edits are `ERROR`, because each one describes data that's wrong or missing, not merely unusual. To show the non-blocking path end to end, I added one extra rule, `student-age-plausible` (WARNING if the student would be older than 120). It comes from the "Age must be between 14 and 120" example in the spec's background section. An age over 120 is almost certainly a typo, but it shouldn't block processing. Severities are configurable, so the policy can be changed without code.

## Assumptions

- **SSN**: "valid format" means exactly 9 ASCII digits with no dashes or spaces. I don't check SSA issuance rules (area 000, 666 or 900+). That would be a separate rule, and I didn't want to reject numbers the spec calls valid.
- **Age** is whole years on today's date in the server's time zone, so a student turns 14 on their birthday. "Today" comes from an injected `Clock`, so tests are pinned to a fixed date.
- **State codes**: the 50 states, DC, and the territories FAFSA accepts (AS, FM, GU, MH, MP, PR, PW, VI). Codes must be uppercase, and `ca` is rejected rather than silently corrected, because the edit's job is to report bad data.
- **Spouse info** means first name, last name and SSN are all present (non-blank). Spouse info sent by a single applicant is ignored.
- **Parent income of 0 is valid**: the rule checks that it was reported, not that it's positive.
- **Income** is `BigDecimal`, because it's money. Cents are allowed.
- **Household counts**: both are required for rule 5. A missing count fails that rule with a message that says so.
- **Enum values** are case-insensitive (`dependent` and `DEPENDENT`). Unknown JSON properties are ignored, so clients can send extra fields.
- **Name fields** aren't validated, because the spec lists no rule for them.

## Production touches

- **Structured logging**: JSON in Elastic Common Schema. One log line per validation, with `status` and `failedRules` as fields. Applications contain PII, so names, SSNs and values are never logged.
- **Metrics** (Micrometer and Actuator): `fafsa.applications.validated{status}` and `fafsa.edits.failed{rule,severity}`. A spike in one rule's failures usually means a client or form bug.
- **Config management**: rule policy is in `application.yml`, overridable per environment with standard Spring property sources (environment variables, command line, config server). Startup validation rejects incomplete config.
- **Health**: `/actuator/health`.

## Testing strategy

84 tests, with 100% line and branch coverage enforced by JaCoCo in `mvn verify`. Only the `main()` launcher is excluded.

| Layer | Test class | Approach |
|---|---|---|
| Rule logic | `FafsaEditRulesTest` | One `@Nested` class per rule. Plain JUnit, no Spring. Boundaries: 14th birthday versus one day before, 8/9/10-digit SSNs, income 0 versus -0.01, college = household. Parameterized tables for SSNs, states and incomes. |
| Engine | `EditEngineTest` | Uses fake rules, so it tests the engine and not FAFSA. Covers collecting all results, order, `NOT_APPLICABLE`, enable flag, severity roll-up, startup drift checks and metrics. |
| Model | `FafsaApplicationTest` | Missing sections become empty, and the status helpers. |
| API | `ValidationApiTest` | Full Spring context with MockMvc and the real `application.yml`. The spec's sample payloads assert exactly the seven expected failures. Also covers batch order and limits, every 400 path, and the metrics endpoint. |

`ApplicationBuilder` (test only) starts from the spec's valid sample, so each test states only the field it's about.

The git history shows the TDD loop: each `test: ... (red)` commit adds failing tests (usually not compiling yet), and the following `feat:` commit makes them pass with `mvn verify` green.

## What I'd do next

- **Mutation testing (PIT)**. 100% coverage shows every branch runs, and PIT would show the assertions actually catch changes.
- **Rule versioning by aid year**: a `fafsa.edits.aid-year` setting and per-year rule sets, with the year echoed in results for auditability.
- **OpenAPI** spec and Swagger UI (springdoc).
- **Correlation ids** in logs and responses.
- **Property-based tests** (jqwik) for the format rules.
- An asynchronous queue-based ingestion path for bulk volume (see Performance).

## Time spent

- Planning with AI (stack, design, testing approach): ~10 minutes
- Implementation, tests and docs with Claude Code: ~35 minutes
- **TODO (Cory):** my own review and walkthrough time, total

I used Claude (via Claude Code) throughout. I made the stack, scope, coverage target and code-style decisions (reusability, readability, conciseness). Claude drafted the code and tests test first, and I reviewed the design and results.
