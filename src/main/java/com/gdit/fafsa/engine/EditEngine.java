package com.gdit.fafsa.engine;

import com.gdit.fafsa.model.FafsaApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies every rule, in order, to an application and collects all results: it never stops at the first
 * failure, so an applicant can fix everything in one pass. The rule set can be swapped at any time; each
 * validation uses the set that was current when it started. Rules are stateless, so one engine can validate
 * many applications concurrently.
 */
@Service
public class EditEngine {

    private static final Logger log = LoggerFactory.getLogger(EditEngine.class);

    private final MeterRegistry meters;
    private volatile List<EditRule> rules = List.of();

    public EditEngine(MeterRegistry meters) {
        this.meters = meters;
    }

    public List<EditRule> rules() {
        return rules;
    }

    public void replaceRules(List<EditRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public ValidationResult validate(FafsaApplication application) {
        var result = ValidationResult.of(rules.stream().map(rule -> rule.evaluate(application)).toList());
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
