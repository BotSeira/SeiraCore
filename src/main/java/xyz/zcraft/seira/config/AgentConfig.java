package xyz.zcraft.seira.config;

public record AgentConfig(
        boolean enabled,
        String endpoint,
        String apiKey,
        Models models,
        String promptFile,
        String toolsFile,
        int timeoutSeconds,
        AgentProxyConfig proxy,
        LLMSettings settings
) {
    public record Models (
            String chat,
            String vision,
            String image,
            String edit,
            String embedding
    ){}
}
