package com.gdit.fafsa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.EditRule;
import com.gdit.fafsa.engine.FieldDefinition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

class ConfigWatcherTest {

    private static final String FIELDS = "fields:\n  - { path: ssn, type: text }\n";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EditEngine engine = new EditEngine(meters);
    private final ConfigLoader loader =
            new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Clock.systemUTC());

    @TempDir
    private Path directory;
    private ConfigWatcher watcher;

    @BeforeEach
    void startWithOneRule() throws IOException {
        write("fields.yml", FIELDS);
        write("checks.yml", "examplesAsOf: 2026-11-01\n");
        write("rules.yml", rules("first"));
        watcher = newWatcher();
    }

    @Test
    void loadsTheFolderAtStartup() {
        assertThat(ruleIds()).containsExactly("first");
    }

    @Test
    void refusesToStartWithAnInvalidConfiguration() throws IOException {
        write("rules.yml", "rules: []");

        assertThatThrownBy(this::newWatcher).isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void doesNothingWhileTheFilesAreUnchanged() {
        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("first");
        assertThat(meters.find("fafsa.config.reloads").counter()).isNull();
    }

    @Test
    void picksUpAddedAndRemovedRulesWhenTheRulesChange() throws IOException {
        write("rules.yml", rules("second", "third"));

        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("second", "third");
        assertThat(meters.counter("fafsa.config.reloads", "result", "success").count()).isEqualTo(1);
    }

    @Test
    void picksUpNewFieldsWhenOnlyTheSchemaChanges() throws IOException {
        write("fields.yml", FIELDS + "  - { path: nickname, type: text }\n");

        watcher.reloadIfChanged();

        assertThat(engine.ruleset().schema().fields()).extracting(FieldDefinition::path)
                .containsExactly("ssn", "nickname");
    }

    @Test
    void keepsTheCurrentConfigurationWhenAChangeBreaksItAndReportsItOnce() throws IOException {
        write("fields.yml", "fields:\n  - { path: socialSecurityNumber, type: text }\n");

        watcher.reloadIfChanged();
        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("first");
        assertThat(engine.ruleset().schema().has("ssn")).isTrue();
        assertThat(meters.counter("fafsa.config.reloads", "result", "rejected").count()).isEqualTo(1);
    }

    @Test
    void keepsTheCurrentConfigurationWhenAFileDisappears() throws IOException {
        Files.delete(directory.resolve("checks.yml"));

        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("first");
    }

    private ConfigWatcher newWatcher() {
        return new ConfigWatcher(engine, loader, new FileSystemResource(directory.toString() + "/"), meters);
    }

    private List<String> ruleIds() {
        return engine.ruleset().rules().stream().map(EditRule::id).toList();
    }

    /** Writes a file and moves its timestamp forward, since two writes can land in the same clock tick. */
    private void write(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        long previous = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(previous + 1000));
    }

    private static String rules(String... ids) {
        var yaml = new StringBuilder("examplesAsOf: 2026-11-01\nrules:\n");
        for (String id : ids) {
            yaml.append("  - { id: ").append(id)
                    .append(", severity: ERROR, message: m, fields: [ssn], required: true,")
                    .append(" examples: { pass: [ { ssn: '1' } ], fail: [ {} ] } }\n");
        }
        return yaml.toString();
    }
}
