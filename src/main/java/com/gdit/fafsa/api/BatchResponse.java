package com.gdit.fafsa.api;

import com.gdit.fafsa.engine.ValidationResult;
import java.util.List;

/** One result per application, in the same order as the request. */
public record BatchResponse(List<ValidationResult> results) {
}
