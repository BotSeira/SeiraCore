package xyz.zcraft.seira.rankguess.data;

import java.util.List;

public record Guess(long rank, long sequence, List<ScoreMultiplier> multipliers, long timestamp) {
    public static Guess of(long rank, long sequence, List<ScoreMultiplier> multipliers) {
        return new Guess(rank, sequence, multipliers, System.currentTimeMillis());
    }
}
