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

        if (osuToken.isExpired()) {
            var newToken = OsuAuthApi.refreshToken(osuToken, bindingConfig.clientId(), bindingConfig.clientSecret());
            if (newToken != null) {
                UserDataStore.storeToken(openId, newToken);
            }
            return newToken;
        } else {
            return osuToken;
        }
    }
}
