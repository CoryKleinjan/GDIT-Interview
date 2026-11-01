package com.gdit.fafsa.api;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.FieldDefinition;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shows the live configuration, e.g. to confirm a change was picked up. The testing page builds its form from it. */
@RestController
@RequestMapping("/api/v1")
public class RulesController {

    private final EditEngine engine;

    public RulesController(EditEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/rules")
    public List<EditRule> rules() {
        return engine.ruleset().rules();
    }

    @GetMapping("/fields")
    public List<FieldDefinition> fields() {
        return engine.ruleset().schema().fields();
    }
}
