package com.gdit.fafsa.engine;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.gdit.fafsa.model.FafsaApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies every enabled edit rule to an application and collects all results (it never stops at the first
 * failure), in the order the rules are registered. Rules are stateless, so one engine can validate many applications concurrently.
 */
@Service
public class EditEngine {

    private static final Logger log = LoggerFactory.getLogger(EditEngine.class);

    private final List<ConfiguredRule> rules;
    private final MeterRegistry meters;

    public EditEngine(List<EditRule> rules, EditRulesProperties properties, MeterRegistry meters) {
        Map<String, EditRule> rulesById = rules.stream().collect(toMap(EditRule::id, identity()));
        if (!rulesById.keySet().equals(properties.rules().keySet())) {
            throw new IllegalStateException("Every edit rule needs exactly one entry under fafsa.edits.rules. "
                    + "Rules in code: " + rulesById.keySet() + ", configured: " + properties.rules().keySet());
        }
        // Results follow rule registration order, so overriding a setting at deploy time never reorders them.
        this.rules = rules.stream()
                .map(rule -> new ConfiguredRule(rule, properties.rules().get(rule.id())))
                .filter(rule -> rule.settings().enabled())
                .toList();
        this.meters = meters;
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

    private record ConfiguredRule(EditRule rule, RuleSettings settings) {
        RuleResult evaluate(FafsaApplication application) {
            return rule.evaluate(application, settings);
        }
    }
}
