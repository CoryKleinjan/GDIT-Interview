package com.gdit.fafsa.api;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.ValidationResult;
import com.gdit.fafsa.model.FafsaApplication;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A request that passes edits and one that fails them both return 200: the edit results are the answer.
 * 400 is reserved for requests the engine cannot evaluate at all.
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
    public ValidationResult validate(@RequestBody FafsaApplication application) {
        return engine.validate(application);
    }

    /** Applications are validated in parallel on the batch pool; results keep the request order. */
    @PostMapping("/validate/batch")
    public BatchResponse validateBatch(@Valid @RequestBody BatchRequest request) {
        List<CompletableFuture<ValidationResult>> validations = request.applications().stream()
                .map(application -> CompletableFuture.supplyAsync(() -> engine.validate(application), batchExecutor))
                .toList();
        return new BatchResponse(validations.stream().map(CompletableFuture::join).toList());
    }
}
