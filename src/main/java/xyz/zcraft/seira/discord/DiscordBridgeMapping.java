package xyz.zcraft.seira.discord;

import java.util.Objects;

public record DiscordBridgeMapping(String groupId, String guildId, String channelId) {
    public DiscordBridgeMapping {
        groupId = requireText(groupId, "groupId");
        guildId = requireText(guildId, "guildId");
        channelId = requireText(channelId, "channelId");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
