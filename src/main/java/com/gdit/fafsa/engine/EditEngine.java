package com.gdit.fafsa.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads an application against the current schema and applies every rule, in order, collecting all results:
 * it never stops at the first failure, so an applicant can fix everything in one pass. The ruleset can be
 * swapped at any time; each validation (or whole batch) uses the ruleset that was current when it started.
 * Rules are stateless, so one engine can validate many applications concurrently.
 */
@Service
public class EditEngine {

    private static final Logger log = LoggerFactory.getLogger(EditEngine.class);

    private final MeterRegistry meters;
    private volatile Ruleset ruleset = Ruleset.EMPTY;

    public EditEngine(MeterRegistry meters) {
        this.meters = meters;
    }

    public Ruleset ruleset() {
        return ruleset;
    }

    public void replace(Ruleset ruleset) {
        this.ruleset = ruleset;
    }

    /** @throws InvalidApplicationException if a field's value does not fit its type */
    public ValidationResult validate(JsonNode application) {
        Ruleset current = ruleset;
        return validate(current.schema().read(application), current);
    }

    /**
     * Validates applications in parallel on {@code executor}; results keep the request order. Every application is
     * read first, so one unreadable application rejects the batch before any work is done.
     *
     * @throws InvalidApplicationException naming each bad field as {@code applications[i].path}
     */
    public List<ValidationResult> validateAll(List<JsonNode> applications, Executor executor) {
        Ruleset current = ruleset;
        List<Application> read = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < applications.size(); i++) {
            try {
                read.add(current.schema().read(applications.get(i), "applications[" + i + "]."));
            } catch (InvalidApplicationException e) {
                problems.addAll(e.problems());
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidApplicationException(problems);
        }
        List<CompletableFuture<ValidationResult>> validations = read.stream()
                .map(application -> CompletableFuture.supplyAsync(() -> validate(application, current), executor))
                .toList();
        return validations.stream().map(CompletableFuture::join).toList();
    }

    private ValidationResult validate(Application application, Ruleset current) {
        var result = ValidationResult.of(current.rules().stream().map(rule -> rule.evaluate(application)).toList());
        record(result);
        return result;
    }

    private void record(ValidationResult result) {
        List<RuleResult> failures = result.results().stream().filter(RuleResult::failed).toList();
        meters.counter("fafsa.applications.validated", "status", result.status().name()).increment();
        failures.forEach(failure -> meters.counter("fafsa.edits.failed",
                "rule", failure.ruleId(), "severity", failure.severity().name()).increment());
        // Rule ids only: applications contain PII (names, SSNs) that must never reach the logs.
        log.atInfo()
                .addKeyValue("status", result.status())
                .addKeyValue("failedRules", failures.stream().map(RuleResult::ruleId).toList())
                .log("Application validated");
    }
}
