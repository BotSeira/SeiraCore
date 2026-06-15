package xyz.zcraft.seira.config;

public record BindingConfig(
        int listenPort,
        String listenPath,
        int clientId,
        String clientSecret
) {
}
