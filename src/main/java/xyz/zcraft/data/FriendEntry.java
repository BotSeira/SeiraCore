package xyz.zcraft.data;

import xyz.zcraft.model.OsuUser;

public record FriendEntry(
        OsuUser user,
        boolean mutual) {
}
