package com.gdit.fafsa.rules;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gdit.fafsa.engine.EditRule;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Reads a rule file and turns it into runnable rules, or rejects it with every problem found. Nothing is
 * returned unless the whole file is valid, so a bad edit can never put a half-working rule set live.
 */
@Component
public class RuleLoader {

    private static final ObjectMapper YAML = JsonMapper.builder(new YAMLFactory())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private final Validator validator;
    private final Clock clock;

    public RuleLoader(Validator validator, Clock clock) {
        this.validator = validator;
        this.clock = clock;
    }

    public List<EditRule> load(Resource resource) {
        RuleFile file = parse(resource);
        List<String> problems = problemsIn(file);
        if (!problems.isEmpty()) {
            throw new InvalidRulesException(resource.getDescription(), problems);
        }
        return file.rules().stream()
                .filter(RuleDefinition::enabled)
                .map(definition -> definition.toEditRule(clock))
                .toList();
    }

    private static RuleFile parse(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return YAML.readValue(in, RuleFile.class);
        } catch (IOException e) {
            throw new InvalidRulesException(resource.getDescription(), List.of(e.getMessage()));
        }
    }

    private List<String> problemsIn(RuleFile file) {
        List<String> problems = new ArrayList<>();
        validator.validate(file).forEach(v -> problems.add(v.getPropertyPath() + " " + v.getMessage()));
        if (problems.isEmpty()) {
            Set<String> ids = new HashSet<>();
            file.rules().forEach(rule -> problems.addAll(problemsIn(rule, ids)));
        }
        return problems;
    }

    private static List<String> problemsIn(RuleDefinition rule, Set<String> idsSoFar) {
        String name = "Rule '" + rule.id() + "' ";
        List<String> problems = new ArrayList<>();
        if (!idsSoFar.add(rule.id())) {
            problems.add(name + "is defined more than once");
        }
        if (!rule.required() && rule.check() == null) {
            problems.add(name + "needs 'required: true', a 'check', or both");
        }
        Class<?> valueType = rule.check() == null ? Object.class : rule.check().valueType();
        rule.fields().forEach(field -> fieldProblem(name, field, valueType, problems));
        if (rule.check() != null) {
            rule.check().referencedFields().forEach(field -> fieldProblem(name, field, valueType, problems));
        }
        if (rule.when() != null) {
            fieldProblem(name, rule.when().field(), Object.class, problems);
        }
        return problems;
    }

    private static void fieldProblem(String name, String field, Class<?> valueType, List<String> problems) {
        if (!Fields.exists(field)) {
            problems.add(name + "uses unknown field '" + field + "'");
        } else if (!valueType.isAssignableFrom(Fields.typeOf(field))) {
            problems.add(name + "applies a " + valueType.getSimpleName() + " check to '" + field
                    + "', which is a " + Fields.typeOf(field).getSimpleName());
        }
    }
}
