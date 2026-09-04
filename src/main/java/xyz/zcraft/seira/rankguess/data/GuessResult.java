package xyz.zcraft.seira.rankguess.data;

import xyz.zcraft.seira.rankguess.RankGuessGameService;

import java.util.List;

public record GuessResult(RankGuessGameService.GuessStatus status, long rank, List<ScoreMultiplier> multipliers,
                          String multiplierString,
                          int guessCount) {

}
