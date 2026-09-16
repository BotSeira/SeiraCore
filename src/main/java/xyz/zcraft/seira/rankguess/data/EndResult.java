package xyz.zcraft.seira.rankguess.data;

public record EndResult(EndStatus status, FinishedRound round, RankType rankType) {
    public enum EndStatus {
        NO_GAME,
        STARTING,
        FORBIDDEN,
        FINISHED
    }

    public enum RankType {
        RANKED,
        NOT_ENOUGH_PARTICIPANT,
        NOT_A_STANDARD_GAME
    }
}
