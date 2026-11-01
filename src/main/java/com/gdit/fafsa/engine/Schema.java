package com.gdit.fafsa.engine;

import static java.util.Objects.requireNonNullElse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The application fields, as defined in {@code fields.yml}. Reads request JSON into an {@link Application},
 * reporting every field whose value does not fit its type. Fields not in the schema are ignored.
 */
public final class Schema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();

    public Schema(List<FieldDefinition> fields) {
        fields.forEach(field -> this.fields.put(field.path(), field));
    }

    public List<FieldDefinition> fields() {
        return List.copyOf(fields.values());
    }

    public boolean has(String path) {
        return fields.containsKey(path);
    }

    public Class<?> typeOf(String path) {
        return fields.get(path).type().valueType();
    }

    public Application read(JsonNode body) {
        return read(body, "");
    }

    /** Reads a request body; {@code prefix} is put before field names in problems, e.g. {@code applications[0].}. */
    public Application read(JsonNode body, String prefix) {
        if (!body.isObject()) {
            throw new InvalidApplicationException(List.of(prefix + "The application must be a JSON object"));
        }
        return read(path -> body.at("/" + path.replace('.', '/')), prefix);
    }

    /** Reads flat {@code path: value} pairs, such as a rule's examples. */
    public Application read(Map<String, ?> values) {
        List<String> unknown = values.keySet().stream().filter(path -> !has(path))
                .map(path -> path + " is not a known field").toList();
        if (!unknown.isEmpty()) {
            throw new InvalidApplicationException(unknown);
        }
        return read(path -> values.get(path) == null ? MissingNode.getInstance() : JSON.valueToTree(values.get(path)), "");
    }

    private Application read(Function<String, JsonNode> lookup, String prefix) {
        Map<String, Object> values = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (FieldDefinition field : fields.values()) {
            JsonNode node = lookup.apply(field.path());
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            List<String> choices = requireNonNullElse(field.values(), List.of());
            field.type().convert(node, choices).ifPresentOrElse(
                    value -> values.put(field.path(), value),
                    () -> problems.add(prefix + field.path() + " " + field.type().expectation(choices)));
        }
        if (!problems.isEmpty()) {
            throw new InvalidApplicationException(problems);
        }
        return new Application(values);
    }
}
