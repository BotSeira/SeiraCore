package xyz.zcraft.platform.qq;

import xyz.zcraft.config.AppConfig;
import xyz.zcraft.platform.BotPlatformAdapter;
import xyz.zcraft.platform.PlatformGatewayClient;
import xyz.zcraft.platform.PlatformMessageSender;
import xyz.zcraft.util.AccessToken;

import java.net.URI;
import java.util.function.Supplier;

public class QqPlatformAdapter implements BotPlatformAdapter {
    private final AppConfig config;

    public QqPlatformAdapter(AppConfig config) {
        this.config = config;
    }

    @Override
    public AccessToken getAccessToken(AppConfig config) {
        return NetworkHelper.getAccessToken(config);
    }

    @Override
    public String getGatewayEndpoint(AccessToken accessToken) {
        return NetworkHelper.getWSSEndpoint(accessToken);
    }

    @Override
    public PlatformMessageSender createMessageSender(Supplier<AccessToken> tokenSupplier) {
        return new MessageSender(tokenSupplier, new CosUploadService(config.platforms().qq().cos()));
    }

    @Override
    public PlatformGatewayClient createGatewayClient(
            URI serverUri,
            AppConfig config,
            Supplier<AccessToken> tokenSupplier,
            PlatformMessageSender messageSender
    ) {
        return new GatewayWebSocketClient(serverUri, config, tokenSupplier, messageSender);
    }
}

