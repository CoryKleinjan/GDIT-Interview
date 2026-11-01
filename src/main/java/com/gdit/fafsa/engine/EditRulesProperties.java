package com.gdit.fafsa.engine;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Per-rule settings keyed by rule id. The YAML order is the order rules are applied and reported in. */
@Validated
@ConfigurationProperties("fafsa.edits")
public record EditRulesProperties(Map<String, @Valid RuleSettings> rules) {
}
