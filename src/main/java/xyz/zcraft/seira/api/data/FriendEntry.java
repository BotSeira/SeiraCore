package xyz.zcraft.seira.api.data;

import xyz.zcraft.osu.model.User;

public record FriendEntry(
        User user,
        boolean mutual) {
}
