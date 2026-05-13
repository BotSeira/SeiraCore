package xyz.zcraft.config;

public record BindingConfig(
        boolean requireLogin,
        int listenPort,
        String listenPath,
        int clientId,
        String clientSecret
) {}
