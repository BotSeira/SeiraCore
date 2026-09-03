package xyz.zcraft.seira.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Owns the application's asynchronous execution resources.
 *
 * <p>Gateway and command work is mostly blocking network I/O, so virtual
 * threads keep the application responsive without an unbounded platform-thread
 * pool. Attachment downloads remain deliberately bounded because every task can
 * hold a complete replay in memory.</p>
 */
public final class ApplicationExecutors implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(ApplicationExecutors.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService gatewayEvents = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("seira-gateway-event-", 0).factory()
    );
    private final ExecutorService commandTasks = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("seira-command-task-", 0).factory()
    );
    private final ExecutorService attachmentDownloads = Executors.newFixedThreadPool(
            4,
            daemonThreadFactory("seira-attachment-")
    );

    public ExecutorService gatewayEvents() {
        return gatewayEvents;
    }

    public ExecutorService commandTasks() {
        return commandTasks;
    }

    public ExecutorService attachmentDownloads() {
        return attachmentDownloads;
    }

    @Override
    public void close() {
        List.of(attachmentDownloads, commandTasks, gatewayEvents).forEach(ExecutorService::shutdown);
        for (ExecutorService executor : List.of(attachmentDownloads, commandTasks, gatewayEvents)) {
            awaitTermination(executor);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while shutting down application executor");
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        return Thread.ofPlatform().daemon().name(prefix, 0).factory();
    }
}
