package xyz.zcraft.bot;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.config.AppConfig;
import xyz.zcraft.util.ThreadHelper;

import java.net.URI;

public class QQBot {
    private static final Logger LOG = LogManager.getLogger(QQBot.class);
    @Getter
    private final TokenManager tokenManager;
    @Getter
    private final CosService cos;
    @Getter
    private final MessageSender sender;

    public QQBot(AppConfig config) {
        LOG.info("Initializing QQBot");

        LOG.info("Getting access token");
        this.tokenManager = new TokenManager(config.qq().appId(), config.qq().appSecret());

        if(config.cos().isConfigured()) {
            LOG.info("Initializing COS service");
            this.cos = new CosService(config.cos());
        } else {
            LOG.info("COS service not configured");
            this.cos = null;
        }

        this.sender = new MessageSender(tokenManager, cos);

        Runtime.getRuntime().addShutdownHook(new Thread(ThreadHelper::close));

        while (true) {
            try {
                LOG.info("Getting wss endpoint");
                String wssEndpoint = QQApi.getWSSEndpoint(tokenManager.getToken());
                LOG.info("Endpoint: {}", wssEndpoint);

                final WSClient client = new WSClient(
                        URI.create(wssEndpoint),
                        config,
                        tokenManager::getToken,
                        sender
                );
                client.connectBlocking();
                LOG.info("Gateway session started");

                while (client.isOpen()) {
                    //noinspection BusyWait
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                LOG.error("Gateway loop failed", e);
            }

            try {
                //noinspection BusyWait
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
