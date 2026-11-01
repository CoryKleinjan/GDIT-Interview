package com.gdit.fafsa.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A test of one present value, written as {@code check: { type: <name>, ... }}. The built-in types below are the
 * primitives; {@code allOf}, {@code anyOf} and {@code not} combine them; any other type name refers to a named
 * check defined in {@code checks.yml}. Whether a missing value fails is the rule's {@code required} flag.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = Check.Named.class, visible = true)
@JsonSubTypes({
        @Type(value = Check.Matches.class, name = "matches"),
        @Type(value = Check.OneOf.class, name = "oneOf"),
        @Type(value = Check.Min.class, name = "min"),
        @Type(value = Check.AtMost.class, name = "atMost"),
        @Type(value = Check.MinAge.class, name = "minAge"),
        @Type(value = Check.MaxAge.class, name = "maxAge"),
        @Type(value = Check.AllOf.class, name = "allOf"),
        @Type(value = Check.AnyOf.class, name = "anyOf"),
        @Type(value = Check.Not.class, name = "not")})
@JsonIgnoreProperties("type")
public sealed interface Check {

    /** The type of value this check works on; the loader rejects checks applied to fields of another type. */
    Class<?> valueType();

    boolean test(Object value, RuleContext context);

    /** Other fields this check reads, with the type each must have. */
    default Map<String, Class<?>> referencedFields() {
        return Map.of();
    }

    /** False when a combination mixes checks for different types of value. */
    default boolean consistent() {
        return true;
    }

    /** Named checks this check refers to. */
    default Stream<String> names() {
        return Stream.empty();
    }

    /** This check with every named check replaced by its definition. */
    default Check resolve(Function<String, Check> definitions) {
        return this;
    }

    /** The whole value matches a regular expression. */
    record Matches(@NotNull Pattern regex) implements Check {
        public Class<?> valueType() {
            return String.class;
        }

        public boolean test(Object value, RuleContext context) {
            return regex.matcher((String) value).matches();
        }
    }

    /** The value is one of a fixed list (exact, case-sensitive). */
    record OneOf(@NotEmpty List<String> values) implements Check {
        public Class<?> valueType() {
            return String.class;
        }

        public boolean test(Object value, RuleContext context) {
            return values.contains(value);
        }
    }

    /** The number is at least {@code value}. */
    record Min(@NotNull BigDecimal value) implements Check {
        public Class<?> valueType() {
            return Number.class;
        }

        public boolean test(Object number, RuleContext context) {
            return decimal(number).compareTo(value) >= 0;
        }
    }

    /** The number is no more than another field's value, which must be present. */
    record AtMost(@NotBlank String field) implements Check {
        public Class<?> valueType() {
            return Number.class;
        }

        public boolean test(Object number, RuleContext context) {
            Object limit = context.application().get(field);
            return limit != null && decimal(number).compareTo(decimal(limit)) <= 0;
        }

        @Override
        public Map<String, Class<?>> referencedFields() {
            return Map.of(field, Number.class);
        }
    }

    /** The person is at least {@code years} old today, in whole years. */
    record MinAge(@NotNull Integer years) implements Check {
        public Class<?> valueType() {
            return LocalDate.class;
        }

        public boolean test(Object dateOfBirth, RuleContext context) {
            return age(dateOfBirth, context) >= years;
        }
    }

    /** The person is at most {@code years} old today, in whole years. */
    record MaxAge(@NotNull Integer years) implements Check {
        public Class<?> valueType() {
            return LocalDate.class;
        }

        public boolean test(Object dateOfBirth, RuleContext context) {
            return age(dateOfBirth, context) <= years;
        }
    }

    /** Shared behaviour of {@code allOf} and {@code anyOf}: all listed checks must work on the same type of value. */
    sealed interface Combination extends Check {
        List<Check> checks();

        default Class<?> valueType() {
            return checks().get(0).valueType();
        }

        @Override
        default boolean consistent() {
            return checks().stream().allMatch(check -> check.consistent()
                    && check.valueType().equals(checks().get(0).valueType()));
        }

        @Override
        default Map<String, Class<?>> referencedFields() {
            Map<String, Class<?>> fields = new HashMap<>();
            checks().forEach(check -> fields.putAll(check.referencedFields()));
            return fields;
        }

        @Override
        default Stream<String> names() {
            return checks().stream().flatMap(Check::names);
        }
    }

    /** Every listed check passes. */
    record AllOf(@NotEmpty List<@Valid Check> checks) implements Combination {
        public boolean test(Object value, RuleContext context) {
            return checks.stream().allMatch(check -> check.test(value, context));
        }

        @Override
        public Check resolve(Function<String, Check> definitions) {
            return new AllOf(checks.stream().map(check -> check.resolve(definitions)).toList());
        }
    }

    /** At least one listed check passes. */
    record AnyOf(@NotEmpty List<@Valid Check> checks) implements Combination {
        public boolean test(Object value, RuleContext context) {
            return checks.stream().anyMatch(check -> check.test(value, context));
        }

        @Override
        public Check resolve(Function<String, Check> definitions) {
            return new AnyOf(checks.stream().map(check -> check.resolve(definitions)).toList());
        }
    }

    /** The inner check fails. */
    record Not(@NotNull @Valid Check check) implements Check {
        public Class<?> valueType() {
            return check.valueType();
        }

        public boolean test(Object value, RuleContext context) {
            return !check.test(value, context);
        }

        @Override
        public boolean consistent() {
            return check.consistent();
        }

        @Override
        public Map<String, Class<?>> referencedFields() {
            return check.referencedFields();
        }

        @Override
        public Stream<String> names() {
            return check.names();
        }

        @Override
        public Check resolve(Function<String, Check> definitions) {
            return new Not(check.resolve(definitions));
        }
    }

    /** A check defined by name in {@code checks.yml}; replaced by its definition when the files are loaded. */
    @JsonIgnoreProperties(value = {})
    record Named(@NotBlank String type) implements Check {
        public Class<?> valueType() {
            throw unresolved();
        }

        public boolean test(Object value, RuleContext context) {
            throw unresolved();
        }

        @Override
        public Stream<String> names() {
            return Stream.of(type);
        }

        @Override
        public Check resolve(Function<String, Check> definitions) {
            return definitions.apply(type);
        }

        private IllegalStateException unresolved() {
            return new IllegalStateException("Check '" + type + "' was used before it was resolved");
        }
    }

    private static BigDecimal decimal(Object number) {
        return new BigDecimal(number.toString());
    }

    private static int age(Object dateOfBirth, RuleContext context) {
        return Period.between((LocalDate) dateOfBirth, context.today()).getYears();
    }
}
