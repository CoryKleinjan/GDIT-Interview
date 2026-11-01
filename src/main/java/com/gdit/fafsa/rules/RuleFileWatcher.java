package com.gdit.fafsa.rules;

import com.gdit.fafsa.engine.EditEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Loads the rule file into the engine at startup. An invalid file stops the service from starting. */
@Component
public class RuleFileWatcher {

    public RuleFileWatcher(
            EditEngine engine, RuleLoader loader, @Value("${fafsa.edits.rules-location}") Resource location) {
        engine.replaceRules(loader.load(location));
    }
}
