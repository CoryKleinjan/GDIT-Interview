package com.gdit.fafsa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BatchConfigurationTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ThreadPoolExecutor executor;

    @AfterEach
    void shutDown() {
        executor.shutdown();
    }

    @Test
    void usesTheConfiguredNumberOfThreads() {
        executor = new BatchConfiguration().batchExecutor(3, meters);

        assertThat(executor.getCorePoolSize()).isEqualTo(3);
    }

    @Test
    void defaultsToOneThreadPerProcessor() {
        executor = new BatchConfiguration().batchExecutor(0, meters);

        assertThat(executor.getCorePoolSize()).isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void namesItsThreadsAndPublishesPoolMetrics() throws Exception {
        executor = new BatchConfiguration().batchExecutor(1, meters);

        assertThat(executor.submit(() -> Thread.currentThread().getName()).get()).startsWith("fafsa-batch-");
        assertThat(meters.find("executor.pool.size").tag("name", "fafsa.batch").gauge()).isNotNull();
    }
}
