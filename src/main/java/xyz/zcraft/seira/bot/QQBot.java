package xyz.zcraft.seira.bot;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.QQUser;
import xyz.zcraft.seira.binding.BindingService;
import xyz.zcraft.seira.command.AttachmentHandler;
import xyz.zcraft.seira.command.route.Router;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.game.RankGuessGameService;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.services.CosService;
import xyz.zcraft.seira.runtime.ApplicationExecutors;
import xyz.zcraft.seira.util.TokenManager;
import xyz.zcraft.seira.watch.OstellaWatchApi;
import xyz.zcraft.seira.watch.QqWatchScoreNotifier;
import xyz.zcraft.seira.watch.ScoreWatchService;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class QQBot implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(QQBot.class);

    @Getter
    private final TokenManager tokenManager;
    @Getter
    private final CosService cos;
    @Getter
    private final MessageSender sender;
    private final ScoreWatchService watchService;
    private final AppConfig config;
    private final Router router;
    private final AttachmentHandler attachmentHandler;
    private final ApplicationExecutors executors;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<WSClient> activeClient = new AtomicReference<>();
    private volatile Thread runnerThread;

    public QQBot(AppConfig config, BindingService bindingService, ApplicationExecutors executors) {
        LOG.info("Initializing QQBot");
        this.config = config;
        this.executors = executors;

        LOG.info("Authorizing QQ API");
        this.tokenManager = new TokenManager(config.qq().appId(), config.qq().appSecret());

        LOG.info("Initializing COS service");
        this.cos = new CosService(config.cos());

        this.sender = new MessageSender(tokenManager, cos);

        LOG.info("Initializing score watch service");
        this.watchService = new ScoreWatchService(
                new OstellaWatchApi(config.ostella().endpoint()),
                new QqWatchScoreNotifier(sender),
                Duration.ofMinutes(config.seira().effectiveWatchIntervalMinutes())
        );

        LOG.info("Initializing rank guess service");
        RankGuessGameService rankGuessGameService = new RankGuessGameService();
        this.attachmentHandler = new AttachmentHandler(executors.attachmentDownloads());
        this.router = new Router(
                sender,
                config,
                bindingService,
                watchService,
                rankGuessGameService,
                executors.commandTasks(),
                BotStat::incrementCommands
        );
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("QQBot has already been closed");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        runnerThread = Thread.currentThread();
        tokenManager.start();
        watchService.start();
        LOG.info("Starting bot connection loop...");

        while (running.get()) {
            try {
                tokenManager.blockUntilValid();
                if (!running.get()) {
                    break;
                }

                LOG.info("Getting wss endpoint");
                String wssEndpoint = QQApi.getWSSEndpoint(tokenManager.getToken());
                LOG.info("Endpoint: {}", wssEndpoint);

                final QQUser self = QQApi.getSelf(tokenManager.getToken());

                LOG.info("Self info: id={}, nickname={}", self.id(), self.username());

                WSClient client = new WSClient(
                        URI.create(wssEndpoint),
                        config,
                        tokenManager::getToken,
                        router,
                        attachmentHandler,
                        executors.gatewayEvents()
                );
                activeClient.set(client);

                CountDownLatch disconnectLatch = new CountDownLatch(1);
                client.setOnCloseCallback(disconnectLatch::countDown);

                try {
                    if (!client.connectBlocking()) {
                        throw new IllegalStateException("Gateway connection attempt failed");
                    }
                    LOG.info("Gateway session started");
                } catch (InterruptedException e) {
                    LOG.error("Connection interrupted", e);
                    Thread.currentThread().interrupt();
                    break;
                }

                try {
                    disconnectLatch.await();
                    LOG.warn("Gateway session closed. Preparing to reconnect...");
                } catch (InterruptedException e) {
                    LOG.warn("Bot interrupted while waiting for disconnect.");
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                if (running.get()) {
                    LOG.error("Gateway loop failed", e);
                }
            } finally {
                activeClient.set(null);
            }

            if (!running.get()) {
                break;
            }
            try {
                //noinspection BusyWait
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                LOG.info("Bot interrupted. Shutting down connection loop.");
                Thread.currentThread().interrupt();
                break;
            }
        }
        runnerThread = null;
        running.set(false);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stop();
        cos.close();
    }

    public void stop() {
        running.set(false);
        WSClient client = activeClient.getAndSet(null);
        if (client != null) {
            client.close();
        }
        Thread thread = runnerThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        watchService.close();
        tokenManager.close();
    }
}
