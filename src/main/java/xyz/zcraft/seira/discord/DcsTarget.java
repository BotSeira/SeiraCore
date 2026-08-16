package xyz.zcraft.seira.discord;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record DcsTarget(String guildId, String channelId) {
    private static final Pattern SYNTAX = Pattern.compile("([0-9]{1,20})\\.([0-9]{1,20})");

    public static DcsTarget parse(String value) {
        if (value == null) return null;
        Matcher matcher = SYNTAX.matcher(value.trim());
        if (!matcher.matches() || !isPositiveSnowflake(matcher.group(1)) || !isPositiveSnowflake(matcher.group(2))) {
            return null;
        }
        return new DcsTarget(matcher.group(1), matcher.group(2));
    }

    private static boolean isPositiveSnowflake(String value) {
        try {
            return Long.parseUnsignedLong(value) != 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
