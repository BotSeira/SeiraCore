package xyz.zcraft.seira.bot;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.util.CosService;
import xyz.zcraft.seira.util.ThreadHelper;
import xyz.zcraft.seira.util.TokenManager;

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

        LOG.info("Initializing COS service");
        this.cos = new CosService(config.cos());

        this.sender = new MessageSender(tokenManager, cos);

        Runtime.getRuntime().addShutdownHook(new Thread(ThreadHelper::close));

        while (true) {
            try {
                tokenManager.refreshToken();

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
