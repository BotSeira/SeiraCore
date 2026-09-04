package xyz.zcraft.seira.rankguess.data;

public record EndResult(EndStatus status, FinishedRound round, boolean recorded) {
    public enum EndStatus {
        NO_GAME,
        STARTING,
        FORBIDDEN,
        FINISHED
    }
}
