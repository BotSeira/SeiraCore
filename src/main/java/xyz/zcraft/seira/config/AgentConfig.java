package xyz.zcraft.seira.config;

public record AgentConfig(
        boolean enabled,
        String endpoint,
        String apiKey,
        Models models,
        String promptFile,
        String toolsFile,
        int timeoutSeconds,
        boolean usePreviousResponseId,
        AgentProxyConfig proxy,
        LLMSettings settings,
        Usages usages
) {
    public record Models(
            String chat,
            String vision,
            String image,
            String edit,
            String embedding
    ) {
    }

    public record Usages(
            boolean enabled,
            String endpoint,
            String path,
            double rate,
            String unit
    ) {
    }
}
