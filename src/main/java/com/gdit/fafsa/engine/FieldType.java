package com.gdit.fafsa.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/** The types a field can have in {@code fields.yml}, and how a JSON value becomes a typed value. */
public enum FieldType {
    TEXT(String.class),
    INTEGER(Long.class),
    NUMBER(BigDecimal.class),
    DATE(LocalDate.class),
    /** Text limited to listed values, matched case-insensitively and stored as listed. */
    CHOICE(String.class);

    private final Class<?> valueType;

    FieldType(Class<?> valueType) {
        this.valueType = valueType;
    }

    public Class<?> valueType() {
        return valueType;
    }

    /** The typed value, or empty when the JSON value does not fit this type. */
    Optional<Object> convert(JsonNode node, List<String> choices) {
        return Optional.ofNullable(switch (this) {
            case TEXT -> node.isTextual() || node.isNumber() ? node.asText() : null;
            case INTEGER -> node.isNumber() && node.canConvertToExactIntegral() ? node.longValue() : null;
            case NUMBER -> node.isNumber() ? node.decimalValue() : null;
            case DATE -> node.isTextual() ? date(node.asText()) : null;
            case CHOICE -> choices.stream().filter(choice -> node.isTextual() && choice.equalsIgnoreCase(node.asText()))
                    .findFirst().orElse(null);
        });
    }

    /** What a valid value looks like, for error messages. */
    String expectation(List<String> choices) {
        return switch (this) {
            case TEXT -> "must be text";
            case INTEGER -> "must be a whole number";
            case NUMBER -> "must be a number";
            case DATE -> "must be a date (YYYY-MM-DD)";
            case CHOICE -> "must be one of " + choices;
        };
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
