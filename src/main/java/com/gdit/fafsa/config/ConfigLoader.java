package com.gdit.fafsa.config;

import static java.util.stream.Collectors.toSet;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gdit.fafsa.config.CheckDefinition.CheckExamples;
import com.gdit.fafsa.engine.Application;
import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.FieldDefinition;
import com.gdit.fafsa.engine.FieldType;
import com.gdit.fafsa.engine.InvalidApplicationException;
import com.gdit.fafsa.engine.Outcome;
import com.gdit.fafsa.engine.Ruleset;
import com.gdit.fafsa.engine.Schema;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Loads the edit configuration from one folder: {@code fields.yml} (the application schema), {@code checks.yml}
 * (named checks) and {@code rules.yml} (the rules). Each file is validated against the ones before it, and every
 * check and rule must behave as its own examples say. Nothing is returned unless all three are valid together, so
 * a bad edit can never put a half-working configuration live.
 */
@Component
public class ConfigLoader {

    private static final ObjectMapper YAML = JsonMapper.builder(new YAMLFactory())
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private static final Set<String> BUILT_IN_CHECKS = Arrays.stream(Check.class.getAnnotation(JsonSubTypes.class)
            .value()).map(JsonSubTypes.Type::name).collect(toSet());

    private final Validator validator;
    private final Clock clock;

    public ConfigLoader(Validator validator, Clock clock) {
        this.validator = validator;
        this.clock = clock;
    }

    public Ruleset load(Resource directory) {
        Schema schema = loadSchema(file(directory, "fields.yml"));
        Map<String, Check> checks = loadChecks(file(directory, "checks.yml"), schema);
        return new Ruleset(schema, loadRules(file(directory, "rules.yml"), schema, checks));
    }

    static Resource file(Resource directory, String name) {
        try {
            return directory.createRelative(name);
        } catch (IOException e) {
            throw new InvalidConfigException(directory.getDescription(), List.of(e.getMessage()));
        }
    }

    // fields.yml

    private Schema loadSchema(Resource resource) {
        FieldFile file = parse(resource, FieldFile.class);
        List<String> problems = constraintProblems(file);
        if (problems.isEmpty()) {
            Set<String> paths = new HashSet<>();
            file.fields().forEach(field -> problems.addAll(fieldProblems(field, paths, file.fields())));
        }
        throwIfAny(resource, problems);
        return new Schema(file.fields());
    }

    private static List<String> fieldProblems(FieldDefinition field, Set<String> pathsSoFar, List<FieldDefinition> all) {
        String name = "Field '" + field.path() + "' ";
        List<String> problems = new ArrayList<>();
        if (!pathsSoFar.add(field.path())) {
            problems.add(name + "is defined more than once");
        }
        boolean hasValues = field.values() != null && !field.values().isEmpty();
        if (field.type() == FieldType.CHOICE && !hasValues) {
            problems.add(name + "is a choice, so it needs 'values'");
        }
        if (field.type() != FieldType.CHOICE && hasValues) {
            problems.add(name + "has 'values' but is not a choice");
        }
        if (all.stream().anyMatch(other -> other.path().startsWith(field.path() + "."))) {
            problems.add(name + "cannot also contain other fields");
        }
        return problems;
    }

    // checks.yml

    private Map<String, Check> loadChecks(Resource resource, Schema schema) {
        CheckFile file = parse(resource, CheckFile.class);
        List<String> problems = constraintProblems(file);
        Map<String, Check> checks = new LinkedHashMap<>();
        if (problems.isEmpty()) {
            Clock asOf = fixedAt(file.examplesAsOf());
            file.checks().forEach(definition -> problems.addAll(addCheck(definition, checks, schema, asOf)));
        }
        throwIfAny(resource, problems);
        return checks;
    }

    /** Validates a named check against the checks defined above it and, if it is valid, adds it to them. */
    private static List<String> addCheck(
            CheckDefinition definition, Map<String, Check> checksSoFar, Schema schema, Clock asOf) {
        String name = "Check '" + definition.name() + "' ";
        List<String> problems = new ArrayList<>();
        if (BUILT_IN_CHECKS.contains(definition.name())) {
            problems.add(name + "has the name of a built-in check");
        }
        if (checksSoFar.containsKey(definition.name())) {
            problems.add(name + "is defined more than once");
        }
        definition.check().names().filter(used -> !checksSoFar.containsKey(used)).forEach(used ->
                problems.add(name + "uses check '" + used + "', which is not built in or defined above it"));
        if (problems.isEmpty()) {
            Check check = definition.check().resolve(checksSoFar::get);
            problems.addAll(checkProblems(name, check, List.of(definition.examples().field()), schema));
            if (problems.isEmpty()) {
                problems.addAll(checkExampleProblems(name, check, definition.examples(), schema, asOf));
            }
            if (problems.isEmpty()) {
                checksSoFar.put(definition.name(), check);
            }
        }
        return problems;
    }

    private static List<String> checkExampleProblems(
            String name, Check check, CheckExamples examples, Schema schema, Clock asOf) {
        List<String> problems = new ArrayList<>();
        for (boolean shouldPass : List.of(true, false)) {
            List<Object> values = shouldPass ? examples.pass() : examples.fail();
            for (int i = 0; i < values.size(); i++) {
                String where = name + "example " + (shouldPass ? "pass" : "fail") + "[" + i + "] ";
                checkExampleProblem(where, check, examples.field(), values.get(i), shouldPass, schema, asOf)
                        .ifPresent(problems::add);
            }
        }
        return problems;
    }

    private static Optional<String> checkExampleProblem(
            String where, Check check, String field, Object value, boolean shouldPass, Schema schema, Clock asOf) {
        Application application;
        try {
            application = schema.read(Collections.singletonMap(field, value));
        } catch (InvalidApplicationException e) {
            return Optional.of(where + e.problems().get(0));
        }
        if (application.get(field) == null) {
            return Optional.of(where + "is empty; checks only see values that are present");
        }
        boolean passed = check.test(application.get(field), new RuleContext(application, LocalDate.now(asOf)));
        return passed == shouldPass
                ? Optional.empty()
                : Optional.of(where + "'" + value + "' should " + (shouldPass ? "pass" : "fail"));
    }

    // rules.yml

    private List<EditRule> loadRules(Resource resource, Schema schema, Map<String, Check> checks) {
        RuleFile file = parse(resource, RuleFile.class);
        List<String> problems = constraintProblems(file);
        if (problems.isEmpty()) {
            Set<String> ids = new HashSet<>();
            file.rules().forEach(rule -> problems.addAll(ruleProblems(rule, ids, schema, checks)));
        }
        throwIfAny(resource, problems);
        List<RuleDefinition> rules = file.rules().stream().map(rule -> rule.resolve(checks::get)).toList();
        Clock asOf = fixedAt(file.examplesAsOf());
        rules.forEach(rule -> problems.addAll(ruleExampleProblems(rule, schema, asOf)));
        throwIfAny(resource, problems);
        return rules.stream()
                .filter(RuleDefinition::enabled)
                .map(rule -> rule.toEditRule(clock))
                .toList();
    }

    private static List<String> ruleProblems(
            RuleDefinition rule, Set<String> idsSoFar, Schema schema, Map<String, Check> checks) {
        String name = "Rule '" + rule.id() + "' ";
        List<String> problems = new ArrayList<>();
        if (!idsSoFar.add(rule.id())) {
            problems.add(name + "is defined more than once");
        }
        if (!rule.required() && rule.check() == null) {
            problems.add(name + "needs 'required: true', a 'check', or both");
        }
        if (rule.check() == null) {
            rule.fields().forEach(field -> fieldProblem(name, field, Object.class, schema).ifPresent(problems::add));
        } else {
            List<String> unknown = rule.check().names().filter(used -> !checks.containsKey(used)).toList();
            unknown.forEach(used ->
                    problems.add(name + "uses check '" + used + "', which is not built in or defined in checks.yml"));
            if (unknown.isEmpty()) {
                problems.addAll(checkProblems(name, rule.check().resolve(checks::get), rule.fields(), schema));
            }
        }
        if (rule.when() != null) {
            fieldProblem(name, rule.when().field(), Object.class, schema).ifPresent(problems::add);
        }
        return problems;
    }

    private static List<String> ruleExampleProblems(RuleDefinition rule, Schema schema, Clock asOf) {
        EditRule editRule = rule.toEditRule(asOf);
        List<String> problems = new ArrayList<>();
        for (Examples.Group group : rule.examples().groups()) {
            for (int i = 0; i < group.examples().size(); i++) {
                String where = "Rule '" + rule.id() + "' example " + group.name() + "[" + i + "] ";
                ruleExampleProblem(where, editRule, group.examples().get(i), group.expected(), schema)
                        .ifPresent(problems::add);
            }
        }
        return problems;
    }

    private static Optional<String> ruleExampleProblem(
            String where, EditRule rule, Map<String, Object> example, Outcome expected, Schema schema) {
        Outcome actual;
        try {
            actual = rule.evaluate(schema.read(example)).outcome();
        } catch (InvalidApplicationException e) {
            return Optional.of(where + String.join("; ", e.problems()));
        }
        return actual == expected
                ? Optional.empty()
                : Optional.of(where + new TreeMap<>(example) + " should be " + expected + " but was " + actual);
    }

    // shared

    /** A check applied to fields: it must not mix value types, and every field it touches must fit it. */
    private static List<String> checkProblems(String name, Check check, List<String> fields, Schema schema) {
        if (!check.consistent()) {
            return List.of(name + "combines checks for different types of value");
        }
        List<String> problems = new ArrayList<>();
        fields.forEach(field -> fieldProblem(name, field, check.valueType(), schema).ifPresent(problems::add));
        check.referencedFields().forEach((field, type) ->
                fieldProblem(name, field, type, schema).ifPresent(problems::add));
        return problems;
    }

    private static Optional<String> fieldProblem(String name, String field, Class<?> required, Schema schema) {
        if (!schema.has(field)) {
            return Optional.of(name + "uses unknown field '" + field + "'");
        }
        if (!required.isAssignableFrom(schema.typeOf(field))) {
            return Optional.of(name + "applies a " + required.getSimpleName() + " check to '" + field
                    + "', which is a " + schema.typeOf(field).getSimpleName());
        }
        return Optional.empty();
    }

    private static <T> T parse(Resource resource, Class<T> type) {
        try (InputStream in = resource.getInputStream()) {
            return YAML.readValue(in, type);
        } catch (IOException e) {
            throw new InvalidConfigException(resource.getDescription(), List.of(e.getMessage()));
        }
    }

    private List<String> constraintProblems(Object file) {
        List<String> problems = new ArrayList<>();
        validator.validate(file).forEach(v -> problems.add(v.getPropertyPath() + " " + v.getMessage()));
        return problems;
    }

    private static void throwIfAny(Resource resource, List<String> problems) {
        if (!problems.isEmpty()) {
            throw new InvalidConfigException(resource.getDescription(), problems);
        }
    }

    private static Clock fixedAt(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }
}
