package xyz.zcraft.seira.rankguess;

import java.util.List;

public record Guess(long rank, long sequence, List<RankGuessGameService.ScoreMultiplier> multipliers, long timestamp) {
    public static Guess of(long rank, long sequence, List<RankGuessGameService.ScoreMultiplier> multipliers) {
        return new Guess(rank, sequence, multipliers, System.currentTimeMillis());
    }
}
