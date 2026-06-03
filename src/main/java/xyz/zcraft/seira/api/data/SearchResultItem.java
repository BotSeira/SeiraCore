package xyz.zcraft.seira.api.data;

public record SearchResultItem(
        long beatmapsetId,
        String artist,
        String title,
        String mapperName,
        double minStar,
        double maxStar,
        String coverUrl
) {
}
