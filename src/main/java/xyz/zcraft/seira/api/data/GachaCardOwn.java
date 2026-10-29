package xyz.zcraft.seira.api.data;

import org.jetbrains.annotations.NotNull;

public record GachaCardOwn(
        String username,
        long count,
        String bestRarity,
        long value,
        long lowest
) {
}
