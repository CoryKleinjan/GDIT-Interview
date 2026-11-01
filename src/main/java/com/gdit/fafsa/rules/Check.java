package com.gdit.fafsa.rules;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The vocabulary a rule file can use, written as {@code check: { type: <name>, ... }}. Checks only see values
 * that are present; whether a missing value fails is the rule's {@code required} flag.
 *
 * <p>A new kind of check is the one change that needs code: add a record here and register its name below.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @Type(value = Check.Matches.class, name = "matches"),
        @Type(value = Check.OneOf.class, name = "oneOf"),
        @Type(value = Check.Min.class, name = "min"),
        @Type(value = Check.AtMost.class, name = "atMost"),
        @Type(value = Check.MinAge.class, name = "minAge"),
        @Type(value = Check.MaxAge.class, name = "maxAge")})
public sealed interface Check {

    /** The type of field this check works on; the loader rejects rules that apply it to anything else. */
    Class<?> valueType();

    boolean test(Object value, RuleContext context);

    /** Other fields this check reads, so the loader can verify they exist. */
    default List<String> referencedFields() {
        return List.of();
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
            Object limit = Fields.read(field, context.application());
            return limit != null && decimal(number).compareTo(decimal(limit)) <= 0;
        }

        @Override
        public List<String> referencedFields() {
            return List.of(field);
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

    private static BigDecimal decimal(Object number) {
        return new BigDecimal(number.toString());
    }

    private static int age(Object dateOfBirth, RuleContext context) {
        return Period.between((LocalDate) dateOfBirth, context.today()).getYears();
    }
}
