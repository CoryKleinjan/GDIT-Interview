package com.gdit.fafsa.engine;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * One application field: its JSON path (for example {@code studentInfo.ssn}) and type. {@code values} lists the
 * allowed values of a {@link FieldType#CHOICE} field.
 */
public record FieldDefinition(
        @NotNull @Pattern(regexp = "[A-Za-z]+(\\.[A-Za-z]+)*", message = "must be dot-separated names") String path,
        @NotNull FieldType type,
        List<String> values) {
}
