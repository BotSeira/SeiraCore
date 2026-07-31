package xyz.zcraft.seira.api.data;

public record ReplayUploadInfo(
        Long scoreId,
        Long beatmapId,
        Long beatmapsetId,
        Long userId,
        String username
) {
}
