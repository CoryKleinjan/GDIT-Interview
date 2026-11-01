package com.gdit.fafsa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gdit.fafsa.engine.EditEngine;
import com.gdit.fafsa.engine.EditRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

class RuleFileWatcherTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EditEngine engine = new EditEngine(meters);
    private final RuleLoader loader =
            new RuleLoader(Validation.buildDefaultValidatorFactory().getValidator(), Clock.systemUTC());

    @TempDir
    private Path directory;
    private Path file;
    private RuleFileWatcher watcher;

    @BeforeEach
    void startWithOneRule() throws IOException {
        file = directory.resolve("rules.yml");
        write(rules("first"));
        watcher = new RuleFileWatcher(engine, loader, new FileSystemResource(file), meters);
    }

    @Test
    void loadsTheFileAtStartup() {
        assertThat(ruleIds()).containsExactly("first");
    }

    @Test
    void refusesToStartWithAnInvalidFile() throws IOException {
        write("rules: []");

        assertThatThrownBy(() -> new RuleFileWatcher(engine, loader, new FileSystemResource(file), meters))
                .isInstanceOf(InvalidRulesException.class);
    }

    @Test
    void doesNothingWhileTheFileIsUnchanged() {
        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("first");
        assertThat(meters.find("fafsa.rules.reloads").counter()).isNull();
    }

    @Test
    void picksUpAddedAndRemovedRulesWhenTheFileChanges() throws IOException {
        write(rules("second", "third"));

        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("second", "third");
        assertThat(meters.counter("fafsa.rules.reloads", "result", "success").count()).isEqualTo(1);
    }

    @Test
    void keepsTheCurrentRulesWhenTheNewFileIsInvalidAndReportsItOnce() throws IOException {
        write("rules: [ broken");

        watcher.reloadIfChanged();
        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("first");
        assertThat(meters.counter("fafsa.rules.reloads", "result", "rejected").count()).isEqualTo(1);
    }

    @Test
    void keepsTheCurrentRulesWhenTheFileDisappears() throws IOException {
        Files.delete(file);

        watcher.reloadIfChanged();

        assertThat(ruleIds()).containsExactly("first");
    }

    private java.util.List<String> ruleIds() {
        return engine.rules().stream().map(EditRule::id).toList();
    }

    /** Writes the file and moves its timestamp forward, since two writes can land in the same clock tick. */
    private void write(String content) throws IOException {
        long previous = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(previous + 1000));
    }

    private static String rules(String... ids) {
        var yaml = new StringBuilder("rules:\n");
        for (String id : ids) {
            yaml.append("  - { id: ").append(id)
                    .append(", severity: ERROR, message: m, fields: [studentInfo.ssn], required: true }\n");
        }
        return yaml.toString();
    }
}
