package xyz.zcraft.seira.config;

public record SeiraConfig(
        String sqlitePath,
        String directUrl,
        boolean debugMode,
        java.util.List<String> adminIds
) {
}
