package xyz.zcraft.seira.config;

public record BindingConfig(
        boolean requireLogin,
        int listenPort,
        String listenPath,
        int clientId,
        String clientSecret
) {
}
