package xyz.zcraft.seira.config;

public record DiscordConfig(String token, DiscordProxyConfig proxy) {
    public DiscordConfig {
        token = token == null ? "" : token;
        proxy = proxy == null ? DiscordProxyConfig.disabled() : proxy;
    }

    public static DiscordConfig disabled() {
        return new DiscordConfig("", DiscordProxyConfig.disabled());
    }

    public boolean enabled() {
        return !token.isBlank();
    }
}
