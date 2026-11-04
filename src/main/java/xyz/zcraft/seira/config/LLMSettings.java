package xyz.zcraft.seira.config;

public record LLMSettings(
        Double temperature,
        Double topP,
        String reasoningEffort,
        Integer maxOutputTokens,
        String textVerbosity
) {
    public LLMSettings {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("agent.settings.temperature must be between 0 and 2");
        }

        if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("agent.settings.topP must be between 0 and 1");
        }

        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("agent.settings.maxOutputTokens must be positive");
        }

        if (reasoningEffort != null && reasoningEffort.isBlank()) {
            throw new IllegalArgumentException("agent.settings.reasoningEffort must not be blank");
        }

        if (textVerbosity != null && !java.util.Set.of("low", "medium", "high").contains(textVerbosity)) {
            throw new IllegalArgumentException("agent.settings.textVerbosity must be low, medium or high");
        }
    }
}
