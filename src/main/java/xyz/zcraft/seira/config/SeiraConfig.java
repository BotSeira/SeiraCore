package xyz.zcraft.seira.config;

public record SeiraConfig(
        String sqlitePath,
        String directUrl,
        boolean queueMessageInGroup,
        Integer watchIntervalMinutes,
        boolean debugMode,
        java.util.List<String> adminIds
) {
    private static final int DEFAULT_WATCH_INTERVAL_MINUTES = 5;

    public int effectiveWatchIntervalMinutes() {
        return watchIntervalMinutes == null || watchIntervalMinutes <= 0
                ? DEFAULT_WATCH_INTERVAL_MINUTES
                : watchIntervalMinutes;
    }
}
