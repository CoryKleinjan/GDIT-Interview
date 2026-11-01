package com.gdit.fafsa.api;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.ValidationResult;
import com.gdit.fafsa.model.FafsaApplication;
import jakarta.validation.Valid;
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

    public ValidationController(EditEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/validate")
    public ValidationResult validate(@RequestBody FafsaApplication application) {
        return engine.validate(application);
    }

    /** Rules are stateless, so applications are validated in parallel; results keep the request order. */
    @PostMapping("/validate/batch")
    public BatchResponse validateBatch(@Valid @RequestBody BatchRequest request) {
        return new BatchResponse(request.applications().parallelStream().map(engine::validate).toList());
    }
}
