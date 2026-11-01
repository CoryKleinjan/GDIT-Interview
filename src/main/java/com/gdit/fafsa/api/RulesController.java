package com.gdit.fafsa.api;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.EditRule;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shows which rules are live right now, e.g. to confirm a rule file change was picked up. */
@RestController
public class RulesController {

    private final EditEngine engine;

    public RulesController(EditEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/api/v1/rules")
    public List<EditRule> rules() {
        return engine.rules();
    }
}
