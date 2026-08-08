package xyz.zcraft.seira.util;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.QQApi;
import xyz.zcraft.seira.bot.data.AccessToken;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TokenManager implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(TokenManager.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "access-token-poller");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final String clientId;
    private final String clientSecret;

    @Getter
    private volatile AccessToken token;

    public TokenManager(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized void start() {
        ensureOpen();
        if (started.get()) {
            return;
        }
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!isValid()) {
                    LOG.info("Token missing or nearing expiration. Initiating background renewal.");
                    renewToken();
                }
            } catch (Exception e) {
                LOG.error("Background token check encountered an error", e);
            }
        }, 0, 60, TimeUnit.SECONDS);
        started.set(true);
    }

    public boolean isValid() {
        AccessToken currentToken = this.token;
        if (currentToken == null) {
            return false;
        }

        long elapsedMillis = System.currentTimeMillis() - currentToken.tokenGrantTime();

        long maxValidMillis = (currentToken.expiresIn() - 120) * 1000L;

        return elapsedMillis < maxValidMillis;
    }

    private synchronized void renewToken() {
        try {
            this.token = QQApi.getAccessToken(clientId, clientSecret);
            LOG.info("Token successfully renewed. Expires in {} seconds", token.expiresIn());
        } catch (Exception e) {
            LOG.error("Failed to fetch new token from QQ API. Will try again on next polling cycle.", e);
        }
    }

    public void blockUntilValid() {
        ensureOpen();
        if (isValid()) {
            return;
        }

        LOG.info("Startup paused: Waiting for a valid QQ API access token...");

        while (!isValid()) {
            ensureOpen();
            synchronized (this) {
                ensureOpen();
                if (isValid()) {
                    break;
                }

                try {
                    this.token = QQApi.getAccessToken(clientId, clientSecret);
                    LOG.info("Startup token successfully acquired. Expires in {} seconds.", token.expiresIn());
                    break;
                } catch (Exception e) {
                    LOG.error("Failed to fetch token during startup. Retrying in 5 seconds...", e);
                    try {
                        //noinspection BusyWait
                        Thread.sleep(5000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Startup interrupted while waiting for access token", ie);
                    }
                }
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Token manager has been closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            started.set(false);
            scheduler.shutdownNow();
        }
    }
}
