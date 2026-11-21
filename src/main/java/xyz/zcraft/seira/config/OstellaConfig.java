package xyz.zcraft.seira.config;

import java.util.Map;

public record OstellaConfig(
        String endpoint,
        String token,
        Map<String, String> toolVerbosity
) {
    public OstellaConfig {
        toolVerbosity = toolVerbosity == null ? Map.of() : Map.copyOf(toolVerbosity);
    }
}
