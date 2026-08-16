package xyz.zcraft.seira.config;

public record BridgeConfig(
        Long maxMediaBytes,
        Long maxDiscordBatchBytes,
        Integer maxDiscordAttachments,
        Integer workerThreads,
        String qqToDiscordFormat,
        String discordToQqFormat
) {
    private static final long DEFAULT_MAX_MEDIA_BYTES = 20L * 1024 * 1024;
    private static final long DEFAULT_MAX_DISCORD_BATCH_BYTES = 23L * 1024 * 1024;

    public BridgeConfig {
        maxMediaBytes = maxMediaBytes == null ? DEFAULT_MAX_MEDIA_BYTES : maxMediaBytes;
        maxDiscordBatchBytes = maxDiscordBatchBytes == null
                ? DEFAULT_MAX_DISCORD_BATCH_BYTES
                : maxDiscordBatchBytes;
        maxDiscordAttachments = maxDiscordAttachments == null ? 10 : maxDiscordAttachments;
        workerThreads = workerThreads == null ? 4 : workerThreads;
        qqToDiscordFormat = qqToDiscordFormat == null ? "[{name}] {message}" : qqToDiscordFormat;
        discordToQqFormat = discordToQqFormat == null ? "[{name}] {message}" : discordToQqFormat;
    }

    public static BridgeConfig defaults() {
        return new BridgeConfig(null, null, null, null, null, null);
    }
}
