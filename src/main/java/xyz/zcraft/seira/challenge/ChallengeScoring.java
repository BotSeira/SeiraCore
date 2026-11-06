package xyz.zcraft.seira.challenge;

import static xyz.zcraft.seira.challenge.ChallengeModels.Result;
import static xyz.zcraft.seira.challenge.ChallengeModels.ScoreData;

/**
 * Version 1: standardised osu! score, adjusted relative to a frozen skill estimate.
 */
public final class ChallengeScoring {
    private ChallengeScoring() {
    }

    public static double multiplier(double stars, double skillStars) {
        if (!Double.isFinite(stars) || stars <= 0 || !Double.isFinite(skillStars) || skillStars <= 0)
            throw new IllegalArgumentException("星数与水平估计必须是正数。");
        double delta = stars - skillStars;
        return delta >= 0 ? Math.min(1.10, 1 + 0.05 * delta) : Math.max(0.50, 1 + 0.20 * delta);
    }

    public static Result calculate(ScoreData score, double skillStars) {
        if (score.totalScore() <= 0 || !Double.isFinite(score.accuracy()) || score.accuracy() < 0
                || score.accuracy() > 1 || score.maxCombo() < 0) throw new IllegalArgumentException("无效成绩数据。");
        double factor = multiplier(score.stars(), skillStars);
        return new Result(score, factor, Math.round(score.totalScore() * factor));
    }

    /**
     * Equal points favour accuracy, then the earlier play; combo counts differ across difficulties.
     */
    public static int compare(Result left, Result right) {
        int points = Long.compare(left.points(), right.points());
        if (points != 0) return points;
        int accuracy = Double.compare(left.score().accuracy(), right.score().accuracy());
        if (accuracy != 0) return accuracy;
        int time = Long.compare(right.score().endedAt(), left.score().endedAt());
        return time != 0 ? time : Long.compare(right.score().id(), left.score().id());
    }
}
