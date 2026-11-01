package com.gdit.fafsa.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class BatchConfiguration {

    /**
     * A pool of its own for batch validation, so a large batch cannot starve the JVM's shared common pool.
     * Validation is CPU-bound, so the default is one thread per processor.
     */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor batchExecutor(@Value("${fafsa.edits.batch-threads}") int threads, MeterRegistry meters) {
        int size = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        var executor = new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name("fafsa-batch-", 0).factory());
        new ExecutorServiceMetrics(executor, "fafsa.batch", List.of()).bindTo(meters);
        return executor;
    }
}
