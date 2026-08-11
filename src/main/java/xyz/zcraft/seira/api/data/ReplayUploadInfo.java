package xyz.zcraft.seira.api.data;

public record ReplayUploadInfo(
        String scoreId,
        Long beatmapId,
        Long beatmapsetId,
        Long userId,
        String username
) {
}
