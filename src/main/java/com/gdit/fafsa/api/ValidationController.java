package com.gdit.fafsa.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.ValidationResult;
import jakarta.validation.Valid;
import java.util.concurrent.Executor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Applications are plain JSON, read against the live schema in {@code fields.yml}. A request that passes edits and
 * one that fails them both return 200: the edit results are the answer. 400 is for requests that cannot be read.
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ValidationController {

    private final EditEngine engine;
    private final Executor batchExecutor;

    public ValidationController(EditEngine engine, Executor batchExecutor) {
        this.engine = engine;
        this.batchExecutor = batchExecutor;
    }

    @PostMapping("/validate")
    public ValidationResult validate(@RequestBody JsonNode application) {
        return engine.validate(application);
    }

    /** Applications are validated in parallel on the batch pool; results keep the request order. */
    @PostMapping("/validate/batch")
    public BatchResponse validateBatch(@Valid @RequestBody BatchRequest request) {
        return new BatchResponse(engine.validateAll(request.applications(), batchExecutor));
    }
}
