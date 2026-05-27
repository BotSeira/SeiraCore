package xyz.zcraft.seira.util;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.QQApi;

import java.util.Timer;

public class TokenManager {
    private static final Logger LOG = LogManager.getLogger(TokenManager.class);
    private static final Timer timer = new Timer("access-token-renewal", true);
    private final String clientId;
    private final String clientSecret;
    @Getter
    private AccessToken token;

    public TokenManager(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean isValid() {
        return (token == null) || (System.currentTimeMillis() - token.tokenGrantTime() >= (token.expiresIn() - 60) * 1000);
    }

    public void refreshToken() {
        LOG.info("Refreshing access token");

        do {
            try {
                token = QQApi.getAccessToken(clientId, clientSecret);
                break;
            } catch (Exception e) {
                LOG.error("Failed to renew token, will retry in 10 sec", e);
            }

            try {
                //noinspection BusyWait
                Thread.sleep(10 * 1000L);
            } catch (InterruptedException _) {
            }
        } while (!isValid());

        LOG.info("Token renewed, expire in {}", token.expiresIn());

        timer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                LOG.info("Scheduled access token renewal in progress");
                refreshToken();
            }
        }, Math.min(token.expiresIn() - 60, 60) * 1000L);
    }
}
