package xyz.zcraft.seira.rankguess;

import java.util.List;

public record FinishedRound(RankGuessGameService.Round round, List<Standing> standings) {
}
