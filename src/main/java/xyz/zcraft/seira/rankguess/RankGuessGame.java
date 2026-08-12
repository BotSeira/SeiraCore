package xyz.zcraft.seira.rankguess;

import lombok.Getter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
public final class RankGuessGame {
    public final UUID token;
    public final String starterUserId;
    public final Map<String, Guess> guesses = new LinkedHashMap<>();
    public final AtomicInteger guessCount = new AtomicInteger(0);
    public RankGuessGameService.Round round;
    public Instant guessingStartedAt;
    public long nextSequence;

    RankGuessGame(UUID token, String starterUserId) {
        this.token = token;
        this.starterUserId = starterUserId;
    }

    public double getMultiplierDelta(RankGuessGameService.ScoreMultiplier multiplier) {
        if (multiplier instanceof RankGuessGameService.ScoreMultiplier.FirstGuessMultiplier) {
            return 0.05;
        } else if (multiplier instanceof RankGuessGameService.ScoreMultiplier.OrderMultiplier orderMultiplier) {
            return Math.max(-0.10, 0.00 - (orderMultiplier.getOrder() - 2) * 0.01);
        } else if (multiplier instanceof RankGuessGameService.ScoreMultiplier.CopyPunishmentMultiplier) {
            if (guessCount.get() >= 10) {
                return -0.025;
            } else {
                return -0.05;
            }
        }
        return 0;
    }

    public String getMultipliersString(List<RankGuessGameService.ScoreMultiplier> multipliers) {
        if (multipliers == null || multipliers.isEmpty()) {
            return "倍率: `x1.00`\n";
        }

        StringBuilder builder = new StringBuilder();

        double sum = multipliers.stream()
                .mapToDouble(this::getMultiplierDelta)
                .sum();

        builder.append("倍率: `x")
                .append(String.format(Locale.US, "%.2f", 1 + sum))
                .append("`\n");

        for (RankGuessGameService.ScoreMultiplier multiplier : multipliers) {
            builder.append("> ")
                    .append(multiplier.getReason())
                    .append(": ")
                    .append(String.format(
                            Locale.US,
                            "%+.0f%%",
                            this.getMultiplierDelta(multiplier) * 100
                    ))
                    .append("\n");
        }

        return builder.toString();
    }
}
