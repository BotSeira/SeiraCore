package xyz.zcraft.seira.util;

import xyz.zcraft.seira.api.OsuAuthApi;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.config.BindingConfig;
import xyz.zcraft.seira.api.data.OsuToken;

public class OsuAuthHelper {
    private final BindingConfig bindingConfig;

    public OsuAuthHelper(BindingConfig bindingConfig) {
        this.bindingConfig = bindingConfig;
    }

    public OsuToken getTokenFor(String openId) {
        final OsuToken osuToken = UserDataStore.findOsuToken(openId);

        if (osuToken == null) {
            return null;
        }

        final OsuToken token = refreshToken(osuToken);
        if (token != null) {
            UserDataStore.storeToken(openId, token);
        }

        return token;
    }

    public OsuToken refreshToken(OsuToken originalToken) {
        if (originalToken.isExpired()) {
            return OsuAuthApi.refreshToken(originalToken, bindingConfig.clientId(), bindingConfig.clientSecret());
        } else {
            return originalToken;
        }
    }
}
