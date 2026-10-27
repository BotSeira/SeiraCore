package xyz.zcraft.seira.services;

import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.config.BindingConfig;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the OAuth callback server and its pending binding requests.
 */
public final class WebServer implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(WebServer.class);

    private final BindingConfig config;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BindService bindService;
    private volatile Javalin server;

    public WebServer(BindingConfig config, BindService bindService) {
        this.config = Objects.requireNonNull(config, "config");
        this.bindService = Objects.requireNonNull(bindService, "bindService");
    }

    public synchronized void start() {
        ensureOpen();
        if (started.get()) {
            return;
        }

        try {
            server = Javalin.create(
                            cfg -> cfg.routes
                                    .get(config.listenPath(), bindService::handleBindingCallback)
                    )
                    .start(config.listenPort());
            started.set(true);
            LOG.info("Binding callback listener started on port {}", config.listenPort());
        } catch (RuntimeException e) {
            Javalin currentServer = server;
            server = null;
            if (currentServer != null) {
                currentServer.stop();
            }
            throw e;
        }
    }

    private void ensureStarted() {
        if (!started.get()) {
            throw new IllegalStateException("Binding service has not been started");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Binding service has been closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        started.set(false);
        Javalin currentServer = server;
        server = null;
        if (currentServer != null) {
            try {
                currentServer.stop();
            } catch (Exception e) {
                LOG.warn("Error stopping server", e);
            }
        }
    }
}
