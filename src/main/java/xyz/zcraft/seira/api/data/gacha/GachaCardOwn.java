package xyz.zcraft.seira.api.data.gacha;

public record GachaCardOwn(
        String username,
        long count,
        String bestRarity,
        long value,
        long lowest
) {
}
