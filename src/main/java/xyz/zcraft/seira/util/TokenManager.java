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

        renew();
    }

    public boolean isExpired() {
        return (token == null) || (System.currentTimeMillis() - token.tokenGrantTime() >= (token.expiresIn() - 60) * 1000);
    }

    public void renew() {
        try {
            token = QQApi.getAccessToken(clientId, clientSecret);
            LOG.info("Token renewed, expire in {}", token.expiresIn());
        } catch (Exception e) {
            token = null;
            LOG.error("Failed to renew token, will retry in 60 sec", e);
        }

        timer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                if (isExpired()) {
                    LOG.info("Access token expired, renewing...");
                    renew();
                }
            }
        }, Math.min((token != null ? token.expiresIn() : 0) - 60, 60) * 1000L);
    }
}
