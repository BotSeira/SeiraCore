package xyz.zcraft.seira.config;

public record SeiraConfig(
        String sqlitePath,
        String directUrl,
        String replayPath,
        boolean queueMessageInGroup,
        boolean debugMode,
        java.util.List<String> adminIds
) {
    public SeiraConfig {
        if (replayPath == null || replayPath.isBlank()) {
            replayPath = "./data/replays";
        }
    }
}
