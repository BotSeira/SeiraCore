package xyz.zcraft.seira.config;

public record SeiraConfig(
        String sqlitePath,
        String directUrl,
        boolean queueMessageInGroup,
        Integer watchIntervalMinutes,
        Integer multiplayerWatchIntervalSeconds,
        boolean debugMode,
        java.util.List<String> adminIds
) {
    private static final int DEFAULT_WATCH_INTERVAL_MINUTES = 5;
    private static final int DEFAULT_MULTIPLAYER_WATCH_INTERVAL_SECONDS = 30;

    public int effectiveWatchIntervalMinutes() {
        return watchIntervalMinutes == null || watchIntervalMinutes <= 0
                ? DEFAULT_WATCH_INTERVAL_MINUTES
                : watchIntervalMinutes;
    }

    public int effectiveMultiplayerWatchIntervalSeconds() {
        return multiplayerWatchIntervalSeconds == null || multiplayerWatchIntervalSeconds <= 0
                ? DEFAULT_MULTIPLAYER_WATCH_INTERVAL_SECONDS
                : multiplayerWatchIntervalSeconds;
    }
}
