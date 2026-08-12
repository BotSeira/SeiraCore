package xyz.zcraft.seira.bot;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.QQUser;
import xyz.zcraft.seira.binding.BindingService;
import xyz.zcraft.seira.command.AttachmentHandler;
import xyz.zcraft.seira.command.route.Router;
import xyz.zcraft.seira.console.ConsoleRuntimeControl;
import xyz.zcraft.seira.console.OstellaCacheControlClient;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.RuntimeConfig;
import xyz.zcraft.seira.discord.DiscordBridgeService;
import xyz.zcraft.seira.rankguess.RankGuessGameService;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.services.CosService;
import xyz.zcraft.seira.runtime.ApplicationExecutors;
import xyz.zcraft.seira.security.AdminRegistry;
import xyz.zcraft.seira.util.TokenManager;
import xyz.zcraft.seira.watch.OstellaWatchApi;
import xyz.zcraft.seira.watch.SpecificScoreNotifier;
import xyz.zcraft.seira.watch.WatchScoreNotifier;
import xyz.zcraft.seira.watch.ScoreWatchService;
import xyz.zcraft.seira.watch.SqliteSpecificScoreWatchStore;
import xyz.zcraft.seira.watch.WatchView;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class QQBot implements AutoCloseable, ConsoleRuntimeControl {
    private static final Logger LOG = LogManager.getLogger(QQBot.class);

    @Getter
    private final TokenManager tokenManager;
    @Getter
    private final CosService cos;
    @Getter
    private final MessageSender sender;
    private final ScoreWatchService watchService;
    private final DiscordBridgeService discordBridgeService;
    private final AppConfig startupConfig;
    private final Router router;
    private final AttachmentHandler attachmentHandler;
    private final ApplicationExecutors executors;
    private final OstellaCacheControlClient cacheControlClient;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<WSClient> activeClient = new AtomicReference<>();
    private volatile Thread runnerThread;

    public QQBot(
            RuntimeConfig runtimeConfig,
            AdminRegistry admins,
            BindingService bindingService,
            ApplicationExecutors executors
    ) {
        LOG.info("Initializing QQBot");
        AppConfig config = runtimeConfig.current();
        this.startupConfig = config;
        this.executors = executors;
        this.cacheControlClient = new OstellaCacheControlClient(config.ostella().endpoint());

        LOG.info("Authorizing QQ API");
        this.tokenManager = new TokenManager(config.qq().appId(), config.qq().appSecret());

        LOG.info("Initializing COS service");
        this.cos = new CosService(config.cos());

        this.sender = new MessageSender(tokenManager, cos);

        LOG.info("Initializing Discord bridge service");
        this.discordBridgeService = new DiscordBridgeService(config.discord(), config.bridge(), sender);

        LOG.info("Initializing score watch service");
        this.watchService = new ScoreWatchService(
                new OstellaWatchApi(config.ostella().endpoint()),
                new WatchScoreNotifier(sender),
                new SpecificScoreNotifier(sender),
                new SqliteSpecificScoreWatchStore(),
                Duration.ofMinutes(config.seira().effectiveWatchIntervalMinutes())
        );

        LOG.info("Initializing rank guess service");
        RankGuessGameService rankGuessGameService = new RankGuessGameService();
        this.attachmentHandler = new AttachmentHandler(executors.attachmentDownloads());
        this.router = new Router(
                sender,
                runtimeConfig::current,
                admins,
                bindingService,
                watchService,
                discordBridgeService,
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
        discordBridgeService.start();
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
                        startupConfig,
                        tokenManager::getToken,
                        router,
                        attachmentHandler,
                        discordBridgeService,
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
        discordBridgeService.close();
        tokenManager.close();
    }

    @Override
    public RuntimeStatus status() {
        WSClient client = activeClient.get();
        ScoreWatchService.Status watchStatus = watchService.status();
        return new RuntimeStatus(
                running.get() && !closed.get(),
                client != null && client.isOpen(),
                tokenManager.isValid(),
                watchStatus.running(),
                watchStatus.groupCount(),
                watchStatus.taskCount(),
                watchStatus.pollInterval()
        );
    }

    @Override
    public boolean reconnectGateway() {
        if (!running.get() || closed.get()) {
            return false;
        }
        WSClient client = activeClient.get();
        if (client == null) {
            return false;
        }
        client.close();
        return true;
    }

    @Override
    public boolean requestWatchPoll() {
        return watchService.requestPoll();
    }

    @Override
    public List<GroupWatches> listWatches() {
        return watchService.listAll().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new GroupWatches(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public List<WatchView> listWatches(String groupId) {
        return watchService.list(groupId);
    }

    @Override
    public WatchView removeWatch(String groupId, long osuUserId) {
        return watchService.remove(groupId, osuUserId);
    }

    @Override
    public int clearWatches(String groupId) {
        return watchService.removeAll(groupId);
    }

    @Override
    public CacheControlResult controlCache(String operation, String type, long id) {
        return cacheControlClient.control(operation, type, id);
    }

    @Override
    public void requestStop() {
        stop();
    }
}
