package xyz.zcraft.seira.rankguess;

import java.util.List;

public record Guess(long rank, long sequence, List<RankGuessGameService.ScoreMultiplier> multipliers) {
}
