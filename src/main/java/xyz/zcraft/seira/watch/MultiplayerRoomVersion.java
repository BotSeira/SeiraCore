package xyz.zcraft.seira.watch;

import java.util.Locale;

public enum MultiplayerRoomVersion {
    LAZER("lazer"),
    STABLE("stable");

    private final String value;

    MultiplayerRoomVersion(String value) {
        this.value = value;
    }

    public static MultiplayerRoomVersion parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lazer" -> LAZER;
            case "stable" -> STABLE;
            default -> null;
        };
    }

    public String value() {
        return value;
    }
}
