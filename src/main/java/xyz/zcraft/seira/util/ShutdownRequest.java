package xyz.zcraft.seira.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs best-effort shutdown preparation with a deadline, then always stops the bot.
 */
public final class ShutdownRequest implements Runnable {
    private static final Logger LOG = LogManager.getLogger(ShutdownRequest.class);
    private final Runnable prepare;
    private final Runnable stop;
    private final Duration timeout;
    private final AtomicBoolean requested = new AtomicBoolean();

    public ShutdownRequest(Runnable prepare, Runnable stop, Duration timeout) {
        this.prepare = prepare;
        this.stop = stop;
        this.timeout = timeout;
    }

    @Override
    public void run() {
        if (!requested.compareAndSet(false, true)) return;
        FutureTask<Void> preparation = new FutureTask<>(prepare, null);
        boolean interrupted = false;
        try {
            // Virtual threads cannot keep the JVM alive if preparation ignores cancellation.
            Thread.ofVirtual().name("seira-shutdown-preparation").start(preparation);
            preparation.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOG.warn("Shutdown preparation exceeded {}; continuing shutdown", timeout);
        } catch (ExecutionException e) {
            LOG.warn("Shutdown preparation failed; continuing shutdown", e.getCause());
        } catch (InterruptedException e) {
            interrupted = true;
            LOG.warn("Shutdown preparation interrupted; continuing shutdown");
        } finally {
            preparation.cancel(true);
            try {
                stop.run();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
