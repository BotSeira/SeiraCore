package xyz.zcraft.seira.bot;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.services.CosService;
import xyz.zcraft.seira.services.DailyLuck;
import xyz.zcraft.seira.util.ThreadHelper;
import xyz.zcraft.seira.util.TokenManager;

import java.net.URI;
import java.util.concurrent.CountDownLatch;

public class QQBot {
    private static final Logger LOG = LogManager.getLogger(QQBot.class);

    @Getter
    private final TokenManager tokenManager;
    @Getter
    private final CosService cos;
    @Getter
    private final MessageSender sender;
    private final AppConfig config;

    public QQBot(AppConfig config) {
        LOG.info("Initializing QQBot");
        this.config = config;

        LOG.info("Authorizing QQ API");
        this.tokenManager = new TokenManager(config.qq().appId(), config.qq().appSecret());

        LOG.info("Initializing COS service");
        this.cos = new CosService(config.cos());

        this.sender = new MessageSender(tokenManager, cos);

        Runtime.getRuntime().addShutdownHook(new Thread(ThreadHelper::close));

        initializeBotStat();
    }

    private void initializeBotStat() {
        LOG.info("Initializing BotStat");
        BotStat.initialize();

        LOG.info("Initializing DailyLuck");
        DailyLuck.initialize(config.qq().appId());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Saving bot stat...");
            BotStat.saveToFile();

            LOG.info("Saving daily luck...");
            DailyLuck.saveToFile();
        }));
    }

    public void start() {
        LOG.info("Starting bot connection loop...");

        while (true) {
            try {
                tokenManager.blockUntilValid();

                LOG.info("Getting wss endpoint");
                String wssEndpoint = QQApi.getWSSEndpoint(tokenManager.getToken());
                LOG.info("Endpoint: {}", wssEndpoint);

                final WSClient client = new WSClient(
                        URI.create(wssEndpoint),
                        config,
                        tokenManager::getToken,
                        sender
                );

                CountDownLatch disconnectLatch = new CountDownLatch(1);
                client.setOnCloseCallback(disconnectLatch::countDown);

                try {
                    client.connectBlocking();
                    LOG.info("Gateway session started");
                } catch (InterruptedException e) {
                    LOG.error("Connection interrupted", e);
                    Thread.currentThread().interrupt();
                    return;
                }

                try {
                    disconnectLatch.await();
                    LOG.warn("Gateway session closed. Preparing to reconnect...");
                } catch (InterruptedException e) {
                    LOG.warn("Bot interrupted while waiting for disconnect.");
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception e) {
                LOG.error("Gateway loop failed", e);
            }

            try {
                //noinspection BusyWait
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                LOG.info("Bot interrupted. Shutting down connection loop.");
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}