package xyz.zcraft.seira.api.data.gacha;

import java.util.List;

public record GachaPlayer(
        Long id,
        String username,
        String countryCode,
        String avatarUrl,
        String coverUrl,
        Long followerCount,
        Long mappingFollowerCount,
        Long rankedMaps,
        Long guestMaps,
        Long lovedMaps,
        Long popularity,
        Long globalRank, Double pp,
        String currentRarity,
        Long mintedCount,
        Boolean pullable,
        String lastSyncedAt,
        String cls,
        String skill,
        String passive,
        Boolean classOverridden,
        Boolean rarityOverridden,
        String name,
        Long founded,
        List<String> members
) {
}
