package xyz.zcraft.seira.config;

public record AgentConfig(
        boolean enabled,
        String endpoint,
        String apiKey,
        String model,
        String promptFile,
        String toolsFile,
        int timeoutSeconds,
        AgentProxyConfig proxy,
        LLMSettings settings
) {
}
