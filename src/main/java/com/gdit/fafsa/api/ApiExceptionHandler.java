package com.gdit.fafsa.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns unusable requests into RFC 9457 problem details that say which field to fix, without echoing
 * submitted values (they may contain SSNs).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String MALFORMED = "Request body is malformed or has the wrong shape.";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", ex.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, describe(ex)),
                headers, status, request);
    }

    private static String describe(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return "Invalid value for field '" + fieldPath(mapping.getPath()) + "'.";
        }
        return MALFORMED;
    }

    /** Renders Jackson's path as e.g. {@code applications[0].household.numberInHousehold}. */
    private static String fieldPath(List<Reference> path) {
        var fieldPath = new StringBuilder();
        for (Reference reference : path) {
            if (reference.getFieldName() == null) {
                fieldPath.append('[').append(reference.getIndex()).append(']');
            } else {
                fieldPath.append(fieldPath.isEmpty() ? "" : ".").append(reference.getFieldName());
            }
        }
        return fieldPath.toString();
    }
}
