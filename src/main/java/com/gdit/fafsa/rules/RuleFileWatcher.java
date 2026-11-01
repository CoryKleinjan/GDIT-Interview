package com.gdit.fafsa.rules;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.EditRule;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the engine's rules in step with the rule file. At startup an invalid file stops the service. After
 * that, the file is checked every {@code fafsa.edits.reload-interval}; a changed file goes live only if it is
 * entirely valid, otherwise the current rules stay and the problems are logged.
 */
@Component
public class RuleFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(RuleFileWatcher.class);

    private final EditEngine engine;
    private final RuleLoader loader;
    private final Resource location;
    private final MeterRegistry meters;
    private long loadedVersion;

    public RuleFileWatcher(EditEngine engine, RuleLoader loader,
            @Value("${fafsa.edits.rules-location}") Resource location, MeterRegistry meters) {
        this.engine = engine;
        this.loader = loader;
        this.location = location;
        this.meters = meters;
        this.loadedVersion = lastModified();
        engine.replaceRules(loader.load(location));
    }

    @Scheduled(fixedDelayString = "${fafsa.edits.reload-interval}")
    public void reloadIfChanged() {
        long version = lastModified();
        if (version == loadedVersion) {
            return;
        }
        loadedVersion = version; // a rejected version is reported once, not on every check
        try {
            engine.replaceRules(loader.load(location));
            meters.counter("fafsa.rules.reloads", "result", "success").increment();
            log.atInfo()
                    .addKeyValue("rules", engine.rules().stream().map(EditRule::id).toList())
                    .log("Edit rules reloaded");
        } catch (InvalidRulesException e) {
            meters.counter("fafsa.rules.reloads", "result", "rejected").increment();
            log.atError()
                    .addKeyValue("problems", e.problems())
                    .log("Edit rules file rejected; keeping the current rules");
        }
    }

    /** A file that is briefly missing (for example, mid-replace) counts as unchanged. */
    private long lastModified() {
        try {
            return location.lastModified();
        } catch (IOException e) {
            return loadedVersion;
        }
    }
}
