package xyz.zcraft.seira.rankguess;

import lombok.Getter;
import xyz.zcraft.seira.bot.data.MessageReference;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
public final class RankGuessGame {
    public static final int COPY_PUNISHMENT_THRESHOLD = 8;
    public final UUID token;
    public final String starterUserId;
    public final boolean fromGroup;
    public final Map<String, Guess> guesses = new LinkedHashMap<>();
    public final AtomicInteger guessCount = new AtomicInteger(0);
    private final List<Hint> revealedHints = new ArrayList<>();
    public boolean copyPunishmentReduced = false;
    public RankGuessGameService.Round round;
    public Instant guessingStartedAt;
    public long nextSequence;
    @Getter
    public MessageReference videoRef = null;
    @Getter
    private volatile boolean ended = false;

    RankGuessGame(UUID token, String starterUserId, boolean fromGroup) {
        this.token = token;
        this.starterUserId = starterUserId;
        this.fromGroup = fromGroup;
    }

    public void markEnded() {
        ended = true;
    }

    public synchronized void revealHint(Hint hint) {
        Objects.requireNonNull(hint, "hint");
        if (!revealedHints.contains(hint)) {
            revealedHints.add(hint);
        }
    }

    public synchronized void revealHints(Collection<Hint> hints) {
        Objects.requireNonNull(hints, "hints").forEach(this::revealHint);
    }

    public synchronized List<Hint> getRevealedHints() {
        return List.copyOf(revealedHints);
    }

    public double getMultiplierDelta(RankGuessGameService.ScoreMultiplier multiplier) {
        if (multiplier instanceof RankGuessGameService.ScoreMultiplier.FirstGuessMultiplier) {
            return 0.05;
        } else if (multiplier instanceof RankGuessGameService.ScoreMultiplier.OrderMultiplier orderMultiplier) {
            return Math.max(-0.10, 0.00 - (orderMultiplier.getOrder() - 2) * 0.01);
        } else if (multiplier instanceof RankGuessGameService.ScoreMultiplier.CopyPunishmentMultiplier) {
            if (guesses.size() >= COPY_PUNISHMENT_THRESHOLD) {
                return -0.025;
            } else {
                return -0.05;
            }
        } else if (multiplier instanceof RankGuessGameService.ScoreMultiplier.HintMultiplier hintMultiplier) {
            return -hintMultiplier.getHint().strength().penalty();
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
                            "%+.2f%%",
                            this.getMultiplierDelta(multiplier) * 100
                    ))
                    .append("\n");
        }

        return builder.toString();
    }

    public double getNextMaxPoints() {
        final double hintsMultiplier = -revealedHints.stream().mapToDouble(h -> h.strength().penalty()).sum();
        final double orderMultiplier = Math.max(-0.10, 0.00 - (guessCount.get() - 2) * 0.01);

        return 1000 * (1 + hintsMultiplier + orderMultiplier);
    }

    public record Hint(String content, String name, HintCategory category, HintStrength strength) {
        public enum HintCategory {
            RANK,
            ACTIVITY,
            BEST_PROFILE,
            TARGET_SCORE,
            DIFFICULTY,
            PLAYSTYLE,
            HISTORY
        }

        public enum HintStrength {
            WEAK(0.01),
            MEDIUM(0.02),
            STRONG(0.03),
            VERY_STRONG(0.04),
            REVEALING(0.05);

            private final double penalty;

            HintStrength(double penalty) {
                this.penalty = penalty;
            }

            public double penalty() {
                return penalty;
            }
        }
    }
}
