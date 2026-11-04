package xyz.zcraft.seira.config;

public record AgentConfig(
        boolean enabled,
        String endpoint,
        String apiKey,
        String model,
        String promptFile,
        String toolsFile,
        int timeoutSeconds,
        Double temperature,
        Double topP,
        String reasoningEffort,
        Integer maxOutputTokens,
        String textVerbosity
) {
    public AgentConfig {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("agent.temperature must be between 0 and 2");
        }
        if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("agent.topP must be between 0 and 1");
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("agent.maxOutputTokens must be positive");
        }

        // Compatible providers may support additional effort levels; leave their validation to the API.
        if (reasoningEffort != null && reasoningEffort.isBlank()) {
            throw new IllegalArgumentException("agent.reasoningEffort must not be blank");
        }
        if (textVerbosity != null && !java.util.Set.of("low", "medium", "high").contains(textVerbosity)) {
            throw new IllegalArgumentException("agent.textVerbosity must be low, medium or high");
        }
    }
}
