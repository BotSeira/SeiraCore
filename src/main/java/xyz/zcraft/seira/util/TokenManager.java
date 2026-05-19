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
        return System.currentTimeMillis() - token.tokenGrantTime() >= (token.expiresIn() - 60) * 1000;
    }

    public void renew() {
        token = QQApi.getAccessToken(clientId, clientSecret);
        LOG.info("Token renewed, expire in {}", token.expiresIn());

        timer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                if (isExpired()) {
                    LOG.info("Access token expired, renewing...");
                    renew();
                }
            }
        }, (token.expiresIn() - 60) * 1000L);
    }
}
