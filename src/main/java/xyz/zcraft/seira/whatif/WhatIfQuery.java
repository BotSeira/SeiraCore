package xyz.zcraft.seira.whatif;

import java.util.regex.Pattern;

/**
 * Total account PP or an osu!standard global rank, never a score's PP.
 */
public record WhatIfQuery(boolean byPp, double pp, long rank) {
    private static final Pattern PP = Pattern.compile("[0-9]+(?:\\.[0-9]+)?pp", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANK = Pattern.compile("#?[0-9]+");

    public static WhatIfQuery parse(String input) {
        if (input != null && PP.matcher(input).matches()) {
            double pp = Double.parseDouble(input.substring(0, input.length() - 2));
            if (Double.isFinite(pp) && pp > 0) return new WhatIfQuery(true, pp, 0);
        } else if (input != null && RANK.matcher(input).matches()) {
            try {
                long rank = Long.parseLong(input.startsWith("#") ? input.substring(1) : input);
                if (rank > 0) return new WhatIfQuery(false, 0, rank);
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IllegalArgumentException("PP 必须为正数并以 pp 结尾；排名必须为正整数。");
    }
}
