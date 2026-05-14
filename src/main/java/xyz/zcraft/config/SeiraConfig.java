package xyz.zcraft.config;

public record SeiraConfig(
        String sqlitePath,
        String directUrl,
        boolean debugMode,
        java.util.List<String> adminIds
) {}
