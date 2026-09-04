package xyz.zcraft.seira.rankguess.data;

import xyz.zcraft.seira.db.RankGuessRecordStore;

public record Rank(double rating, double ratingRaw, String rank) {
    public static final int RECENT_GAME_LIMIT = 10;

    public static Rank from(RankGuessRecordStore.Statistics.Personal recent,
                            RankGuessRecordStore.Statistics.Personal all) {
        final double pendingRatingRaw = getRatingRaw(recent, all);
        final double pendingRating = standardRating(pendingRatingRaw);
        final String pendingRank = getRankText(all.participation(), pendingRatingRaw);

        return new Rank(pendingRating, pendingRatingRaw, pendingRank);
    }

    private static double getRatingRaw(RankGuessRecordStore.Statistics.Personal recent,
                                       RankGuessRecordStore.Statistics.Personal all) {
        double averageScoreRate = Math.clamp(
                recent.averageScore() / 900.0,
                0.0, 1.0
        );

        double rawRating = averageScoreRate * 0.55
                + recent.winRate() * 0.10
                + recent.topTwentyRate() * 0.35;

        rawRating *= 1.025;

        double confidence = 1.0 - Math.exp(-all.participation() / 10.0);

        return 0.50 * (1.0 - confidence) + rawRating * confidence;
    }

    private static double standardRating(double ratingRaw) {
        double z = 2 * ratingRaw - 1;
        double p = 1.1;

        return 1 + Math.copySign(Math.pow(Math.abs(z), p), z);
    }

    private static String getRankText(long totalParticipation, double rawRating) {
        if (totalParticipation < 5) {
            return "?";
        }

        String rank;

        if (rawRating >= 1.00) rank = "SS";
        else if (rawRating >= 0.82) rank = "S";
        else if (rawRating >= 0.68) rank = "A";
        else if (rawRating >= 0.57) rank = "B";
        else if (rawRating >= 0.42) rank = "C";
        else rank = "D";

        if (totalParticipation < 10) {
            rank += "?";
        } else if (rawRating >= 1.00) {
            rank += "（强强！？！）";
        }

        return rank;
    }
}