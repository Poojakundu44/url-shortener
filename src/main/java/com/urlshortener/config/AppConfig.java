package com.urlshortener.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Application-wide infrastructure configuration.
 *
 * @EnableAsync: activates @Async method interception.
 *   Without this, @Async is silently ignored — another common gotcha.
 *
 * @EnableScheduling: activates @Scheduled method execution.
 *   Used for the expired URL cleanup job below.
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /**
     * Bounded thread pool for analytics processing.
     *
     * WHY these numbers?
     * CorePoolSize 2: always 2 threads alive for analytics (low overhead)
     * MaxPoolSize 10: burst up to 10 under load
     * QueueCapacity 500: buffer up to 500 pending events before rejecting
     *
     * INTERVIEW: "What happens when the queue is full?"
     * Default rejection policy: CallerRunsPolicy — the calling thread
     * processes the task itself. This creates backpressure: the redirect
     * thread slows down rather than dropping events silently.
     *
     * At truly high scale (millions req/s): replace this with Kafka.
     * The queue becomes a Kafka topic with durable persistence and
     * independent consumer scaling.
     */
    @Bean(name = "analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("analytics-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}