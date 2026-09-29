package xyz.zcraft.seira.watch;

import java.util.Locale;

public enum MPVersion {
    LAZER("lazer"),
    STABLE("stable");

    private final String value;

    MPVersion(String value) {
        this.value = value;
    }

    public static MPVersion parse(String value) {
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
