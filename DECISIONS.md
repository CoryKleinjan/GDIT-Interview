# Design decisions

## Goals I optimized for

1. **Correct and consistent.** Every enabled rule runs on every application. A broken configuration, or a check or rule that doesn't behave as its own examples say, can never go live. The build enforces 100% line and branch coverage.
2. **Rules change without a deployment.** "Rules may be added or modified during the year" is the constraint that shaped the design most. In review it grew to cover the application's fields and the checks themselves.
3. **Reusable, readable, concise.** A small vocabulary of checks is reused by every rule, and records replace boilerplate.

## Stack

**Java 21 and Spring Boot 3.5**, because that's the team's stack. Records, sealed interfaces and pattern matching keep the code short. Spring gives validation, problem details, scheduling, Actuator and Micrometer. The only extra dependency is `jackson-dataformat-yaml`, for reading the configuration files. Maven runs through the wrapper.

## The open questions

### Rule representation: three declarative YAML files

The whole edit configuration is data, in one folder, [`src/main/resources/edits/`](src/main/resources/edits/):

| File | Holds | Why it's separate |
|---|---|---|
| `fields.yml` | every field a request can carry, by JSON path, and its type (`text`, `integer`, `number`, `date`, `choice`) | Fields change least, and everything else is checked against them |
| `checks.yml` | named, reusable checks built from the built-in checks with `allOf`, `anyOf` and `not` | A check like "nine digits" is written and proven once, then reused by any rule |
| `rules.yml` | the rules, in priority order | What policy owners edit most |

A rule looks like this:

```yaml
- id: married-household-includes-spouse
  severity: ERROR
  message: A married student's household must include the student and spouse, so number in household must be at least 2.
  fields: [household.numberInHousehold]
  required: true
  check: { type: min, value: 2 }
  when: { field: maritalStatus, equals: married }
```

Each rule has the same small shape: which **fields**, whether they're **required**, an optional **check** for present values, an optional **when** condition, and its **examples**. Six built-in checks (`matches`, `oneOf`, `min`, `atMost`, `minAge`, `maxAge`) and three combinators (`allOf`, `anyOf`, `not`) cover all 11 shipped rules. Each built-in is a short Java record in `Check.java`, so the primitives are type-safe and unit tested, while checks built from them, the fields and the rules are configuration.

**Why this over the alternatives:**

| Option | Change without deploy | Safety | Verdict |
|---|---|---|---|
| Java code per rule (my first version) | No | Compile-time | Fails the core constraint. The git history shows the move away from it. |
| **Declarative files + composable checks** | **Yes** | **Validated on load: unknown fields, wrong types, typos and bad regexes are all rejected** | **Chosen** |
| Expression language (SpEL or similar) in config | Yes | Errors surface only at run time, and an injection risk needs sandboxing | Too powerful for these rules and harder to review |
| Database table | Yes | Needs an admin UI, migrations and an extra runtime dependency | Overkill for 11 rules. The loader could read from a database later without other changes. |

The trade-off: a genuinely new *primitive* (say, "SSN not on a deny-list held in a database") needs a small code change, a new `Check` record. That's deliberate. Most new checks are combinations, for example "nine digits and not starting with 9" is `allOf: [nineDigits, not: { matches: '9.*' }]`, and need no code. Only logic that can't be expressed that way gets compiled and tested.

I chose combinators over an expression language for the same reason as above: `allOf`/`anyOf`/`not` over typed primitives can be checked completely at load time (every check in a combination must work on the same type of value, and it must fit the field it's applied to), while an expression only fails when it runs.

**A check or rule ships with its own tests.** A named check has value examples, for example `nineDigits` must pass `'123456789'` and fail `'123-45-6789'`, read as the type of a field it names. Every rule must have `examples`: partial applications it must pass, fail, and (optionally) not apply to, for example:

```yaml
  examples:
    pass: [ { maritalStatus: married, household.numberInHousehold: 2 } ]
    fail: [ { maritalStatus: married, household.numberInHousehold: 1 }, { maritalStatus: married } ]
    notApplicable: [ { maritalStatus: single, household.numberInHousehold: 1 } ]
```

The loader runs every example whenever the files are loaded, at startup and on every hot reload. A check or rule with no examples, or whose results differ from them, gets the whole change rejected. This is what makes "add a rule or check with only a YAML entry" safe: the entry contains its own tests, and they run even when the rule changes without a deployment and no CI pipeline is involved. A file-level `examplesAsOf` date pins "today" for examples, so `2012-11-01 is exactly 14` stays true forever. I checked it by mutation: changing `student-age` to 13 years makes the shipped file fail to load with `example fail[0] {studentInfo.dateOfBirth=2012-11-02} should be FAILED but was PASSED`.

**Fields are a schema, not a Java model.** My first version had a `FafsaApplication` record and a registry of getters, so a new field meant a deployment. Now `fields.yml` lists each field's path and type, and the engine reads a request's JSON against it into typed values (`Long`, `BigDecimal`, `LocalDate`, ...). That keeps what the Java model gave: the loader still rejects `minAge` on a text field, a rule on an unknown field, or a field removed while a rule still uses it, and a request with `"numberInHousehold": "four"` is still a 400 naming the field. The cost is that the schema is checked at load time rather than compile time, which is what the examples and 100% coverage of the loader are for.

### Changing the configuration at runtime

`fafsa.edits.config-location` points at the configuration folder. In production this would be outside the jar, for example a mounted ConfigMap or a shared volume. `ConfigWatcher` checks the three files' timestamps every 10 seconds:

- **The three files go live together.** A change goes live only if all three are valid together: rules only use fields and checks that exist, checks fit their fields' types, and every check and rule passes its examples. Otherwise the current configuration stays, the problems are logged together, and `fafsa.config.reloads{result=rejected}` is incremented. So removing a field that a rule still uses is rejected, rather than breaking the rule.
- **Fail fast at startup.** An invalid configuration at startup stops the service, so it never runs with no rules.
- **Atomic swap.** The engine holds the schema and rules as one immutable `Ruleset` in a `volatile` field, so a reload swaps both in one write. A validation that's already running finishes with the configuration it started with, and no application is ever read with one schema and checked with another's rules.
- **No restart needed.** I chose timestamp polling over a `WatchService` because it works the same on network mounts and ConfigMap symlink swaps, where file-system events are unreliable.

`GET /api/v1/rules` and `GET /api/v1/fields` show exactly what's live, so an operator can confirm a change was picked up.

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

Missing data is always reported by a rule, never as a 400, because finding missing information is exactly what edits are for. A missing field or whole section (`household`, `income`, ...) is simply an absent value.

A 400 lists every field that doesn't fit its type at once (`applications[0].household.numberInHousehold must be a whole number`), for the same reason every rule runs: fix it all in one go. Messages never echo the submitted value, because values can be SSNs.

### Rule conflicts

Two rules can fail together, but they can't contradict each other, because each rule checks one fact and none reads another's result. Rule *files* can conflict, though, so the loader guards against that:

- **Duplicate ids** are rejected, so two definitions can never compete for the same result.
- **One owner per concern.** "Is it present?" is a rule's `required` flag, and "Is it valid?" is its `check`. Present-only checks don't fail on missing values. For example, `non-negative-income` passes a missing parent income, and `dependent-parent-income` is the rule that reports it as missing. So one mistake isn't reported twice.
- **Severity conflicts** are resolved by the roll-up, where the worst failure wins: any ERROR → `INVALID`, otherwise any WARNING → `VALID_WITH_WARNINGS`, otherwise `VALID`.

### Performance

- **Concurrent by design.** Rules are stateless predicates over an immutable record, and the rule list is immutable. So the engine is safe to call from many threads at once with no locking: every HTTP request is validated on its own Tomcat thread, and reloads never block validations.
- **The batch endpoint** validates applications in parallel on a **dedicated thread pool** and keeps results in request order. It doesn't use `parallelStream()`, because that runs on the JVM-wide common pool, where one large batch could starve unrelated parallel work. The pool is sized by `fafsa.edits.batch-threads` (default: one thread per processor, since the work is CPU-bound). Its threads are named `fafsa-batch-N`, and its size, activity and queue length are published as `executor.*` metrics. Batches are capped at 1000 to bound memory and request time.
- **Cheap per application.** One pass over the 14 schema fields, then about 11 predicate calls with no I/O. Regexes and named checks are compiled once, when the files are loaded, and field lookups are map reads with no reflection.
- **What I deliberately didn't parallelize:** the rules within one application. Eleven microsecond-scale checks cost less than handing work between threads, so parallelism pays off across applications, not across rules. Virtual threads wouldn't help either, because the work is CPU-bound.
- **At FAFSA scale** (millions of applications, with a peak around October 1), I'd put the engine behind a queue (Kafka or SQS) and scale consumers horizontally, rather than grow the HTTP batch. The engine has no HTTP dependency, so it would be reused unchanged.

### Extensibility

| Change | What it takes |
|---|---|
| Add, remove, reorder or disable a rule; change a message, severity or threshold | Edit `rules.yml`, including the rule's examples. It's live within 10 seconds, with no deployment and no Java. |
| A new application field | One line in `fields.yml`. Same: live within 10 seconds, no Java. |
| A new check built from existing ones | An entry in `checks.yml`, with value examples. Same. |
| A new built-in primitive | One record in `Check.java`, plus a test |

For example, validating the spouse's SSN format needs no code:

```yaml
- id: spouse-ssn-format
  severity: ERROR
  message: Spouse SSN must be exactly 9 digits.
  fields: [spouseInfo.ssn]
  check: { type: nineDigits }               # the named check from checks.yml
  when: { field: maritalStatus, equals: married }
  examples:
    pass: [ { maritalStatus: married, spouseInfo.ssn: '987654321' }, { maritalStatus: married } ]
    fail: [ { maritalStatus: married, spouseInfo.ssn: '98765-4321' } ]
    notApplicable: [ { maritalStatus: single } ]
```

The `engine` package knows nothing about FAFSA or YAML. With the fields now in `fields.yml` too, the same jar would validate another form given a different configuration folder.

### Severity levels

| Severity | Meaning | Effect on status |
|---|---|---|
| `ERROR` | The application can't be processed until this is fixed | → `INVALID` |
| `WARNING` | Suspicious but processable, so the applicant should confirm it | → `VALID_WITH_WARNINGS` |
| `INFO` | Informational only | none |

**Severity is reported only on failures.** A severity describes how much a *failure* matters, so `PASSED` and `NOT_APPLICABLE` results carry neither `severity` nor `message`. Showing `"severity": "ERROR"` next to a passed rule read as if something was wrong. A rule's configured severity is still visible at `GET /api/v1/rules`.

All the required edits are `ERROR`, because each one describes data that's wrong or missing, not merely unusual. `student-age-plausible` (older than 120) is a `WARNING`. It comes from the spec's "Age must be between 14 and 120" example: an age over 120 is almost certainly a typo, but it shouldn't block processing. Severity is one line in `rules.yml`, so policy changes don't need a developer.

## Assumptions

- **SSN**: "valid format" means exactly 9 ASCII digits with no dashes or spaces. I don't check SSA issuance rules (area 000, 666 or 900+). Those could be added as a `matches` rule without code.
- **Age** is whole years on today's date in the server's time zone, so a student turns 14 on their birthday. "Today" comes from an injected `Clock`, so tests are pinned to a fixed date.
- **State codes**: the 50 states, DC, and the territories FAFSA accepts (AS, FM, GU, MH, MP, PR, PW, VI). Codes must be uppercase, and `ca` is rejected rather than silently corrected, because the edit's job is to report bad data.
- **Married household**: a married student's household must be at least 2 (student and spouse).
- **Spouse info** means first name, last name and SSN are all present (non-blank). Spouse info sent by a single applicant is ignored.
- **Blank text counts as missing** for `required` fields.
- **Parent income of 0 is valid**: the rule checks that it was reported, not that it's positive.
- **Income** is `BigDecimal`, because it's money. Cents are allowed.
- **Choice values** are case-insensitive (`dependent` and `DEPENDENT`). Fields not in `fields.yml` are ignored in a request, but unknown properties in the **configuration files** are rejected, because there a typo would silently change a rule.
- **Integers must be whole numbers in JSON** (`4`, not `"4"` or `4.5`), and text fields accept JSON numbers as text, so an SSN sent as `123456789` is still checked as nine digits.

## Production touches

- **Structured logging**: JSON in Elastic Common Schema. One line per validation (`status`, `failedRules`), and one per configuration reload (the field count and live rule ids, or the problems found). Applications contain PII, so names, SSNs and values are never logged.
- **Metrics** (Micrometer and Actuator): `fafsa.applications.validated{status}`, `fafsa.edits.failed{rule,severity}` and `fafsa.config.reloads{result}`. A spike in one rule's failures right after a reload is the signal that a rule change went wrong.
- **Config management**: the configuration folder and reload interval are ordinary Spring properties, overridable per environment.
- **Health**: `/actuator/health`.
- **Manual testing UI** at `/`: one static HTML page with plain JavaScript, served by Spring. I chose it over Swagger UI, which only gives raw JSON in and out, and over a separate React app, which would add a Node build chain to review and run. The page validates through the real endpoints, highlights the fields that failed, and shows the live rule list. Its form is built from `GET /api/v1/fields`, so a field added to `fields.yml` appears in it without a deployment. So any configuration edit can be checked by hand within seconds, with no restart.

## Testing strategy

There are two layers. The **code** is covered by 162 generic JUnit tests at 100% line and branch coverage, enforced by JaCoCo in `mvn verify` (only the `main()` launcher is excluded). The **configuration** is covered by 74 examples that live next to it: 55 in `rules.yml` and 19 in `checks.yml`. None of the Java tests is specific to one rule, check or FAFSA field, so changing the configuration never needs a Java change.

| What | Test class | Approach |
|---|---|---|
| Every shipped check and rule | `ShippedConfigTest` + the examples in `checks.yml` and `rules.yml` | Loads the real folder as production does, which runs all 74 examples: the 14th birthday versus one day later, 8/9/10-digit SSNs with dashes, letters and spaces, income 0 versus -0.01, college = household, a married household of 1 versus 2, and so on. Also asserts priority order and severities. |
| Checks | `CheckTest` | Each built-in, with boundaries; `allOf`/`anyOf`/`not`; type consistency; named-check resolution |
| Reading requests | `SchemaTest` | Each field type: what it accepts, what's a 400 and its wording, nested paths, every problem reported at once |
| Rule semantics | `RuleDefinitionTest` | `required` versus missing or blank values, multiple fields, `when` conditions |
| Configuration safety | `ConfigLoaderTest` | Each way each of the three files can be wrong is rejected with a clear problem, including checks and rules that contradict their examples. All problems are reported at once. |
| Hot reload | `ConfigWatcherTest` | Uses a real temp folder to test pickup of rule and field changes, rejection of a bad change (old configuration kept and reported once), a missing file, and startup failure |
| Engine | `EditEngineTest` | Uses fake rules, so it tests the engine and not FAFSA: collecting all results, order, `NOT_APPLICABLE`, severity roll-up, rule swapping and metrics |
| Batch pool | `BatchConfigurationTest` | Pool sizing, thread names and metrics |
| API | `ValidationApiTest` | Full Spring context with MockMvc. The spec's sample payloads assert exactly the seven expected failures. Also covers a 200-application batch keeping its order across threads, batch limits, every 400 path, the live rules and fields endpoints, the testing page being served, and metrics. |

The `Applications` test helper builds an application from path/value pairs, so each test states only the fields it's about.

The git history shows the TDD loop: each `test: ... (red)` commit adds failing tests (usually not compiling yet), and the following `feat:` commit makes them pass with `mvn verify` green. It also shows the design changing after review: rules moved from Java code to a hot-reloaded file, per-rule Java tests moved into examples in that file, and then the checks and the application's fields moved into configuration too.

## What I'd do next

- **Configuration versioning by aid year**: one configuration folder per aid year, with the version echoed in results for auditability.
- **An audit trail of configuration changes**: keep the configuration folder in git, or log a hash of the live files with each reload.
- **Mutation testing (PIT)**. 100% coverage shows every branch runs, and PIT would show the assertions actually catch changes.
- **OpenAPI** spec and Swagger UI (springdoc).
- **Correlation ids** in logs and responses.
- An asynchronous queue-based ingestion path for bulk volume (see Performance).

## Time spent

- Planning with AI (stack, design, testing approach): ~10 minutes
- First implementation (rules in code), tests and docs with Claude Code: ~35 minutes
- Design review and rework (rules in a hot-reloaded file, priority order, new household rule, self-testing rule examples, batch pool, severity only on failures): ~40 minutes
- Manual testing UI: ~10 minutes
- Fields and named checks in YAML (schema-driven requests, `fields.yml`, `checks.yml`, one hot-reloaded folder, form built from the schema): ~35 minutes
- **TODO (Cory):** my own review and walkthrough time, total

I used Claude (via Claude Code) throughout. I made the stack, scope, coverage target and code-style decisions, and the design calls in review: rules changeable without deployment and added with only a YAML entry, priority order with marital status before household, always running every rule, the married-household rule, a dedicated batch pool, reporting severity only on failures, a manual testing UI, and moving checks and application fields into YAML as well. Claude drafted the code and tests test first, and I reviewed the design and results.
