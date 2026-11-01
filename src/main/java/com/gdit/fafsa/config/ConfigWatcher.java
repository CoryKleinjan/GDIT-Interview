package com.gdit.fafsa.config;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.EditRule;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the engine in step with the configuration folder. At startup an invalid configuration stops the service.
 * After that, the three files are checked every {@code fafsa.edits.reload-interval}; a change goes live only if
 * the whole configuration is valid, otherwise the current one stays and the problems are logged.
 */
@Component
public class ConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);
    private static final List<String> FILES = List.of("fields.yml", "checks.yml", "rules.yml");

    private final EditEngine engine;
    private final ConfigLoader loader;
    private final Resource directory;
    private final MeterRegistry meters;
    private String loadedVersion;

    public ConfigWatcher(EditEngine engine, ConfigLoader loader,
            @Value("${fafsa.edits.config-location}") Resource directory, MeterRegistry meters) {
        this.engine = engine;
        this.loader = loader;
        this.directory = directory;
        this.meters = meters;
        this.loadedVersion = version();
        engine.replace(loader.load(directory));
        log.atInfo().addKeyValue("location", directory.getDescription()).log("Edit configuration loaded; watching for changes");
    }

    @Scheduled(fixedDelayString = "${fafsa.edits.reload-interval}")
    public void reloadIfChanged() {
        String version = version();
        if (version.equals(loadedVersion)) {
            return;
        }
        loadedVersion = version; // a rejected version is reported once, not on every check
        try {
            engine.replace(loader.load(directory));
            meters.counter("fafsa.config.reloads", "result", "success").increment();
            log.atInfo()
                    .addKeyValue("fields", engine.ruleset().schema().fields().size())
                    .addKeyValue("rules", engine.ruleset().rules().stream().map(EditRule::id).toList())
                    .log("Edit configuration reloaded");
        } catch (InvalidConfigException e) {
            meters.counter("fafsa.config.reloads", "result", "rejected").increment();
            log.atError()
                    .addKeyValue("problems", e.problems())
                    .log("Edit configuration rejected; keeping the current one");
        }
    }

    /** The files' modification times. A file that is briefly missing (for example, mid-replace) counts as unchanged. */
    private String version() {
        var version = new StringBuilder();
        try {
            for (String file : FILES) {
                version.append(ConfigLoader.file(directory, file).lastModified()).append(' ');
            }
        } catch (IOException e) {
            return loadedVersion;
        }
        return version.toString();
    }
}
