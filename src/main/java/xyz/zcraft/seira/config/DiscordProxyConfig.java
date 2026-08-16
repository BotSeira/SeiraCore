package xyz.zcraft.seira.config;

public record DiscordProxyConfig(
        Boolean enabled,
        String host,
        Integer port,
        String username,
        String password
) {
    public DiscordProxyConfig {
        enabled = enabled == null ? Boolean.FALSE : enabled;
        host = host == null ? "127.0.0.1" : host;
        port = port == null ? 7890 : port;
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }

    public static DiscordProxyConfig disabled() {
        return new DiscordProxyConfig(false, "127.0.0.1", 7890, "", "");
    }
}
