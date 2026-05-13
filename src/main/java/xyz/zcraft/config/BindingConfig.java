package xyz.zcraft.config;

public record BindingConfig(
        boolean requireLogin,
        int listenPort,
        int clientId,
        String clientSecret
) {}
