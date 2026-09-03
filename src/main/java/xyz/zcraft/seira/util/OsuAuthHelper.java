package xyz.zcraft.seira.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.OsuAuthApi;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.config.BindingConfig;

public class OsuAuthHelper {
    private static final Logger LOG = LogManager.getLogger();
    private final BindingConfig bindingConfig;

    public OsuAuthHelper(BindingConfig bindingConfig) {
        this.bindingConfig = bindingConfig;
    }

    public OsuToken updateTokenAndGet(String openId) {
        final OsuToken osuToken = UserDataStore.findOsuToken(openId);

        if (osuToken == null) {
            return null;
        }

        if (osuToken.isExpired()) {
            LOG.debug("Auto refreshing token for openId {}", openId);
            final OsuToken newToken = refreshToken(osuToken);
            if (newToken != null) {
                UserDataStore.storeToken(openId, newToken);
            }
            return newToken;
        }

        return osuToken;
    }

    private OsuToken refreshToken(OsuToken originalToken) {
        return OsuAuthApi.refreshToken(originalToken, bindingConfig.clientId(), bindingConfig.clientSecret());
    }

    public record TokenStore(
            String openId,
            OsuToken osuToken
    ) {
    }
}
