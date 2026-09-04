package xyz.zcraft.seira.rankguess.data;

import xyz.zcraft.seira.rankguess.RankGuessGame;
import xyz.zcraft.seira.rankguess.RankGuessGameService;

public record GuessResponse(RankGuessGame game, GuessResult guessResult, String message) {
    public static GuessResponse ofStatus(RankGuessGameService.GuessStatus status) {
        return new GuessResponse(null, new GuessResult(status, 0, null, null, 0), null);
    }

    public static GuessResponse of(RankGuessGame game, GuessResult guessResult, String message) {
        return new GuessResponse(game, guessResult, message);
    }
}
