package xyz.zcraft.seira.rankguess.data;

public record Standing(
        String senderUserId,
        long guess,
        double pointsRaw,
        double multiplier,
        double points,
        long delta,
        double error,
        long sequence
) {
}
