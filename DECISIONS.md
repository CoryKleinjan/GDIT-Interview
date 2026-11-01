# Design decisions

## Goals I optimized for

1. **Correct and consistent.** Every enabled rule runs on every application. A broken rule file, or a rule that doesn't behave as its own examples say, can never go live. The build enforces 100% line and branch coverage.
2. **Rules change without a deployment.** "Rules may be added or modified during the year" is the constraint that shaped the design most.
3. **Reusable, readable, concise.** A small vocabulary of checks is reused by every rule, and records replace boilerplate.

## Stack

**Java 21 and Spring Boot 3.5**, because that's the team's stack. Records, sealed interfaces and pattern matching keep the code short. Spring gives validation, problem details, scheduling, Actuator and Micrometer. The only extra dependency is `jackson-dataformat-yaml`, for reading the rule file. Maven runs through the wrapper.

## The open questions

### Rule representation: a declarative YAML file built from a fixed vocabulary of checks

Rules are data, in [`rules.yml`](src/main/resources/rules.yml):

```yaml
- id: married-household-includes-spouse
  severity: ERROR
  message: A married student's household must include the student and spouse, so number in household must be at least 2.
  fields: [household.numberInHousehold]
  required: true
  check: { type: min, value: 2 }
  when: { field: maritalStatus, equals: married }
```

Each rule has the same small shape: which **fields**, whether they're **required**, an optional **check** for present values, an optional **when** condition, and its **examples**. Six check types (`matches`, `oneOf`, `min`, `atMost`, `minAge`, `maxAge`) cover all 11 shipped rules. Each check is a short Java record in `Check.java`, so the vocabulary is type-safe and unit tested, while the rules themselves are configuration.

**Why this over the alternatives:**

| Option | Change without deploy | Safety | Verdict |
|---|---|---|---|
| Java code per rule (my first version) | No | Compile-time | Fails the core constraint. The git history shows the move away from it. |
| **Declarative file + check vocabulary** | **Yes** | **Validated on load: unknown fields, wrong types, typos and bad regexes are all rejected** | **Chosen** |
| Expression language (SpEL or similar) in config | Yes | Errors surface only at run time, and an injection risk needs sandboxing | Too powerful for these rules and harder to review |
| Database table | Yes | Needs an admin UI, migrations and an extra runtime dependency | Overkill for 11 rules. The loader could read from a database later without other changes. |

The trade-off: a genuinely new *kind* of check (say, "SSN not on a deny-list") needs a small code change, a new `Check` record. That's deliberate. New logic gets compiled and tested, while new *rules* built from existing logic don't.

**A rule ships with its own tests.** Every rule must have `examples`: partial applications it must pass, fail, and (optionally) not apply to, for example:

```yaml
  examples:
    pass: [ { maritalStatus: married, household.numberInHousehold: 2 } ]
    fail: [ { maritalStatus: married, household.numberInHousehold: 1 }, { maritalStatus: married } ]
    notApplicable: [ { maritalStatus: single, household.numberInHousehold: 1 } ]
```

The loader runs every example whenever the file is loaded, at startup and on every hot reload. A rule with no examples, or whose results differ from them, gets the whole file rejected. This is what makes "add a rule with only a YAML entry" safe: the entry contains its own tests, and they run even when the rule changes without a deployment and no CI pipeline is involved. A file-level `examplesAsOf` date pins "today" for examples, so `2012-11-01 is exactly 14` stays true forever. I checked it by mutation: changing `student-age` to 13 years makes the shipped file fail to load with `example fail[0] {studentInfo.dateOfBirth=2012-11-02} should be FAILED but was PASSED`.

**Field access** goes through an explicit registry (`Fields.java`), a map from JSON path to type and getter, rather than reflection. It's type-safe, lists exactly what rules can reference, and lets the loader reject `minAge` on a text field before the file goes live.

### Changing rules at runtime

`fafsa.edits.rules-location` points at the rule file. In production this would be an external file, for example a mounted ConfigMap or a file on a shared volume. `RuleFileWatcher` checks the file's timestamp every 10 seconds:

- **Whole-file validation.** A changed file goes live only if every rule in it is valid and passes its examples. Otherwise the current rules stay, the problems are logged together, and `fafsa.rules.reloads{result=rejected}` is incremented. Partial rule sets never go live.
- **Fail fast at startup.** An invalid file at startup stops the service, so it never runs with no rules.
- **Atomic swap.** The engine holds an immutable list in a `volatile` field, so a reload swaps it in one write. A validation that's already running finishes with the rule set it started with, and no application is ever checked against half of each set.
- **No restart needed.** I chose timestamp polling over a `WatchService` because it works the same on network mounts and ConfigMap symlink swaps, where file-system events are unreliable.

`GET /api/v1/rules` shows exactly what's live, so an operator can confirm a change was picked up.

### Rule priority: file order, and every rule always runs

Order matters for the person reading the results. **Marital and dependency status come first**, because they decide which later rules apply: an unmarried student has no spouse info, and a dependent student needs parent income. **Household rules come after marital status**, because marital status determines who the household must include. That's the reason for the `married-household-includes-spouse` rule: a married student's household must be at least 2, since it includes the spouse.

Rules run and are reported in **file order**, so priority is visible and editable in one place. I considered numeric priorities, but file order says the same thing more readably and can't produce ties.

I also considered gating, where rules are skipped when a prerequisite such as marital status fails. I rejected it so the applicant can **fix every problem in a single resubmission**. Every rule always runs. If marital status is missing, `marital-status` fails and the married-only rules report `NOT_APPLICABLE`, because their `when` condition doesn't hold. That's accurate, and the failure right above them explains why.

### Error handling: collect all, never fail fast

Every rule runs and every result is returned, including passes and `NOT_APPLICABLE`. Rules are cheap, so there's no performance reason to stop early.

There are two kinds of "bad input", and they're kept separate:

| | Response | Examples |
|---|---|---|
| **Edit failures:** the application is readable but wrong or incomplete | **200** with `status: INVALID` and the failed rules | bad SSN, negative income, missing parent income, missing marital status |
| **Unreadable requests:** there's nothing to evaluate | **400** problem detail (RFC 9457) | malformed JSON, `"numberInHousehold": "four"`, `"maritalStatus": "sometimes"`, empty or oversized batch |

Missing data is always reported by a rule, never as a 400, because finding missing information is exactly what edits are for. Missing whole sections (`household`, `income`, ...) become empty records, so rules never need null checks on sections.

400 messages name the field (`Invalid value for field 'applications[0].household.numberInHousehold'.`) but never echo the submitted value, because values can be SSNs.

### Rule conflicts

Two rules can fail together, but they can't contradict each other, because each rule checks one fact and none reads another's result. Rule *files* can conflict, though, so the loader guards against that:

- **Duplicate ids** are rejected, so two definitions can never compete for the same result.
- **One owner per concern.** "Is it present?" is a rule's `required` flag, and "Is it valid?" is its `check`. Present-only checks don't fail on missing values. For example, `non-negative-income` passes a missing parent income, and `dependent-parent-income` is the rule that reports it as missing. So one mistake isn't reported twice.
- **Severity conflicts** are resolved by the roll-up, where the worst failure wins: any ERROR → `INVALID`, otherwise any WARNING → `VALID_WITH_WARNINGS`, otherwise `VALID`.

### Performance

- **Concurrent by design.** Rules are stateless predicates over an immutable record, and the rule list is immutable. So the engine is safe to call from many threads at once with no locking: every HTTP request is validated on its own Tomcat thread, and reloads never block validations.
- **The batch endpoint** validates applications in parallel on a **dedicated thread pool** and keeps results in request order. It doesn't use `parallelStream()`, because that runs on the JVM-wide common pool, where one large batch could starve unrelated parallel work. The pool is sized by `fafsa.edits.batch-threads` (default: one thread per processor, since the work is CPU-bound). Its threads are named `fafsa-batch-N`, and its size, activity and queue length are published as `executor.*` metrics. Batches are capped at 1000 to bound memory and request time.
- **Cheap per application.** About 11 predicate calls with no I/O. Regexes are compiled once, when the file is loaded, and field lookups are direct getters with no reflection.
- **What I deliberately didn't parallelize:** the rules within one application. Eleven microsecond-scale checks cost less than handing work between threads, so parallelism pays off across applications, not across rules. Virtual threads wouldn't help either, because the work is CPU-bound.
- **At FAFSA scale** (millions of applications, with a peak around October 1), I'd put the engine behind a queue (Kafka or SQS) and scale consumers horizontally, rather than grow the HTTP batch. The engine has no HTTP dependency, so it would be reused unchanged.

### Extensibility

| Change | What it takes |
|---|---|
| Add, remove, reorder or disable a rule; change a message, severity or threshold | Edit `rules.yml`, including the rule's examples. It's live within 10 seconds, with no deployment and no Java. |
| A new kind of check | One record in `Check.java`, plus a test |
| A new application field | The model record, plus one line in `Fields.java` |

For example, validating the spouse's SSN format needs no code:

```yaml
- id: spouse-ssn-format
  severity: ERROR
  message: Spouse SSN must be exactly 9 digits.
  fields: [spouseInfo.ssn]
  check: { type: matches, regex: '\d{9}' }
  when: { field: maritalStatus, equals: married }
  examples:
    pass: [ { maritalStatus: married, spouseInfo.ssn: '987654321' }, { maritalStatus: married } ]
    fail: [ { maritalStatus: married, spouseInfo.ssn: '98765-4321' } ]
    notApplicable: [ { maritalStatus: single } ]
```

The `engine` package knows nothing about FAFSA or YAML, so it would also work for another form.

### Severity levels

| Severity | Meaning | Effect on status |
|---|---|---|
| `ERROR` | The application can't be processed until this is fixed | → `INVALID` |
| `WARNING` | Suspicious but processable, so the applicant should confirm it | → `VALID_WITH_WARNINGS` |
| `INFO` | Informational only | none |

**Severity is reported only on failures.** A severity describes how much a *failure* matters, so `PASSED` and `NOT_APPLICABLE` results carry neither `severity` nor `message`. Showing `"severity": "ERROR"` next to a passed rule read as if something was wrong. A rule's configured severity is still visible at `GET /api/v1/rules`.

All the required edits are `ERROR`, because each one describes data that's wrong or missing, not merely unusual. `student-age-plausible` (older than 120) is a `WARNING`. It comes from the spec's "Age must be between 14 and 120" example: an age over 120 is almost certainly a typo, but it shouldn't block processing. Severity is one line in the rule file, so policy changes don't need a developer.

## Assumptions

- **SSN**: "valid format" means exactly 9 ASCII digits with no dashes or spaces. I don't check SSA issuance rules (area 000, 666 or 900+). Those could be added as a `matches` rule without code.
- **Age** is whole years on today's date in the server's time zone, so a student turns 14 on their birthday. "Today" comes from an injected `Clock`, so tests are pinned to a fixed date.
- **State codes**: the 50 states, DC, and the territories FAFSA accepts (AS, FM, GU, MH, MP, PR, PW, VI). Codes must be uppercase, and `ca` is rejected rather than silently corrected, because the edit's job is to report bad data.
- **Married household**: a married student's household must be at least 2 (student and spouse).
- **Spouse info** means first name, last name and SSN are all present (non-blank). Spouse info sent by a single applicant is ignored.
- **Blank text counts as missing** for `required` fields.
- **Parent income of 0 is valid**: the rule checks that it was reported, not that it's positive.
- **Income** is `BigDecimal`, because it's money. Cents are allowed.
- **Enum values** are case-insensitive (`dependent` and `DEPENDENT`). Unknown JSON properties in an application are ignored, but unknown properties in the **rule file** are rejected, because there a typo would silently change a rule.

## Production touches

- **Structured logging**: JSON in Elastic Common Schema. One line per validation (`status`, `failedRules`), and one per rule reload (the live rule ids, or the problems found). Applications contain PII, so names, SSNs and values are never logged.
- **Metrics** (Micrometer and Actuator): `fafsa.applications.validated{status}`, `fafsa.edits.failed{rule,severity}` and `fafsa.rules.reloads{result}`. A spike in one rule's failures right after a reload is the signal that a rule change went wrong.
- **Config management**: the rule file location and reload interval are ordinary Spring properties, overridable per environment.
- **Health**: `/actuator/health`.
- **Manual testing UI** at `/`: one static HTML page with plain JavaScript, served by Spring. I chose it over Swagger UI, which only gives raw JSON in and out, and over a separate React app, which would add a Node build chain to review and run. The page validates through the real endpoints, highlights the fields that failed, and shows the live rule list. So a rule-file edit can be checked by hand within seconds, with no restart.

## Testing strategy

There are two layers. The **code** is covered by 99 generic JUnit tests at 100% line and branch coverage, enforced by JaCoCo in `mvn verify` (only the `main()` launcher is excluded). The **rules** are covered by 55 examples that live next to them in `rules.yml`. None of the Java tests is specific to one rule, so adding a rule never needs a Java change.

| What | Test class | Approach |
|---|---|---|
| Every shipped rule | `ShippedRulesTest` + the examples in `rules.yml` | Loads the real file as production does, which runs all 55 examples: the 14th birthday versus one day later, 8/9/10-digit SSNs with dashes, letters and spaces, income 0 versus -0.01, college = household, a married household of 1 versus 2, and so on. Also asserts priority order and severities. |
| Check vocabulary | `CheckTest`, `FieldsTest` | Each check type, with boundaries, and every registered field |
| Rule semantics | `RuleDefinitionTest` | `required` versus missing or blank values, multiple fields, `when` conditions |
| Rule file safety | `RuleLoaderTest` | Each way a file can be wrong is rejected with a clear problem, including rules that contradict their examples. All problems are reported at once. |
| Hot reload | `RuleFileWatcherTest` | Uses a real temp file to test pickup of changes, rejection of a bad file (old rules kept and reported once), a missing file, and startup failure |
| Engine | `EditEngineTest` | Uses fake rules, so it tests the engine and not FAFSA: collecting all results, order, `NOT_APPLICABLE`, severity roll-up, rule swapping and metrics |
| Batch pool | `BatchConfigurationTest` | Pool sizing, thread names and metrics |
| API | `ValidationApiTest` | Full Spring context with MockMvc. The spec's sample payloads assert exactly the seven expected failures. Also covers a 200-application batch keeping its order across threads, batch limits, every 400 path, the live rules endpoint, the testing page being served, and metrics. |

`ApplicationBuilder` (test only) starts from the spec's valid sample, so each test states only the field it's about.

The git history shows the TDD loop: each `test: ... (red)` commit adds failing tests (usually not compiling yet), and the following `feat:` commit makes them pass with `mvn verify` green. It also shows the design changing after review: rules moved from Java code to a hot-reloaded file, then per-rule Java tests moved into examples in that file.

## What I'd do next

- **Rule versioning by aid year**: one rule file per aid year, with the version echoed in results for auditability.
- **An audit trail of rule changes**: keep rule files in git, or log a hash of the live file with each reload.
- **Mutation testing (PIT)**. 100% coverage shows every branch runs, and PIT would show the assertions actually catch changes.
- **OpenAPI** spec and Swagger UI (springdoc).
- **Correlation ids** in logs and responses.
- An asynchronous queue-based ingestion path for bulk volume (see Performance).

## Time spent

- Planning with AI (stack, design, testing approach): ~10 minutes
- First implementation (rules in code), tests and docs with Claude Code: ~35 minutes
- Design review and rework (rules in a hot-reloaded file, priority order, new household rule, self-testing rule examples, batch pool, severity only on failures): ~40 minutes
- Manual testing UI: ~10 minutes
- **TODO (Cory):** my own review and walkthrough time, total

I used Claude (via Claude Code) throughout. I made the stack, scope, coverage target and code-style decisions, and the design calls in review: rules changeable without deployment and added with only a YAML entry, priority order with marital status before household, always running every rule, the married-household rule, a dedicated batch pool, reporting severity only on failures, and a manual testing UI. Claude drafted the code and tests test first, and I reviewed the design and results.
