package xyz.zcraft.seira.rankguess;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FinishedRound(
        UUID id, String groupId, boolean fromGroup, Instant startedAt, Instant endedAt,
        int scoringVersion, RankGuessGameService.Round round, List<Standing> standings
) {
    public FinishedRound {
        standings = List.copyOf(standings);
    }
}
