package xyz.zcraft.seira.config;

public record AgentProxyConfig(
        boolean enabled,
        String host,
        int port
) {
}
