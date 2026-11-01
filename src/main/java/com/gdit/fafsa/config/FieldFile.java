package com.gdit.fafsa.config;

import com.gdit.fafsa.engine.FieldDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The root of {@code fields.yml}: the application schema. */
record FieldFile(@NotEmpty List<@Valid FieldDefinition> fields) {
}
