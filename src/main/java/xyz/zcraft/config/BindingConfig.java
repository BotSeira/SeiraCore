package xyz.zcraft.config;

public record BindingConfig(
        boolean requireLogin,
        int listenPort,
        String callbackUrl
) {}
