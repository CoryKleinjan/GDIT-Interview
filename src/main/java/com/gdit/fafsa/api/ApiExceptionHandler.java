package com.gdit.fafsa.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import com.gdit.fafsa.engine.InvalidApplicationException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns unusable requests into RFC 9457 problem details that say which field to fix, without echoing
 * submitted values (they may contain SSNs).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ProblemDetail invalidApplication(InvalidApplicationException ex) {
        return withErrors(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Some fields have values that do not fit their type."), ex.problems());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .toList();
        return handleExceptionInternal(ex, withErrors(ex.getBody(), errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, describe(ex)),
                headers, status, request);
    }

    private static ProblemDetail withErrors(ProblemDetail problem, List<String> errors) {
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Only the batch wrapper is bound to a type, so a mapping error names one of its properties. */
    private static String describe(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return "Invalid value for field '" + mapping.getPath().stream()
                    .map(Reference::getFieldName).collect(Collectors.joining(".")) + "'.";
        }
        return "Request body is malformed or has the wrong shape.";
    }
}
