package xyz.zcraft.seira.game;

import xyz.zcraft.seira.api.data.RandomScore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class RankGuessGameService {
    private static final Duration END_PROTECTION_DURATION = Duration.ofMinutes(3);

    private final Map<String, Game> games = new HashMap<>();
    private final Clock clock;

    public RankGuessGameService() {
        this(Clock.systemUTC());
    }

    RankGuessGameService(Clock clock) {
        this.clock = clock;
    }

    public synchronized Reservation reserve(String groupId, String starterUserId) {
        if (games.containsKey(groupId)) {
            return null;
        }

        Reservation reservation = new Reservation(groupId, UUID.randomUUID());
        games.put(groupId, new Game(reservation.token(), starterUserId));
        return reservation;
    }

    public synchronized boolean activate(Reservation reservation, Round round) {
        if (round == null) {
            throw new IllegalArgumentException("Round must not be null");
        }
        Game game = games.get(reservation.groupId());
        if (game == null || !game.token.equals(reservation.token())) {
            return false;
        }

        game.round = round;
        game.guessingStartedAt = clock.instant();
        return true;
    }

    public synchronized void cancel(Reservation reservation) {
        Game game = games.get(reservation.groupId());
        if (game != null && game.token.equals(reservation.token())) {
            games.remove(reservation.groupId());
        }
    }

    public synchronized GuessResult guess(String groupId, String senderUserId, long guess) {
        if (guess <= 0) {
            throw new IllegalArgumentException("Rank must be positive");
        }

        Game game = games.get(groupId);
        if (game == null) {
            return new GuessResult(GuessStatus.NO_GAME, guess, null);
        }
        if (game.round == null) {
            return new GuessResult(GuessStatus.STARTING, guess, null);
        }

        Guess previousGuess = game.guesses.put(senderUserId, new Guess(guess, game.nextSequence++));

        final int guessNumber = game.guessCount.incrementAndGet();

        LinkedList<ScoreMultiplier> multipliers = new LinkedList<>();

        double orderMultiplier = Math.max(
                -0.10,
                0.05 - (guessNumber - 1) * 0.01
        );

        multipliers.add(new ScoreMultiplier(
                orderMultiplier,
                guessNumber == 1 ? "首猜加成" : "第" + guessNumber + "猜"
        ));

        long closestGuess = Arrays.stream(
                        game.guesses.values().stream()
                                .mapToLong(Guess::rank)
                                .toArray()
                ).boxed()
                .min(Comparator.comparingLong(previous ->
                        Math.abs(previous - guess)
                ))
                .orElse(-1L);

        if (closestGuess != -1) {
            boolean copied =
                    Math.abs(closestGuess - guess) <= 100 ||
                            Math.abs(Math.log10(closestGuess)
                                    - Math.log10(guess)) < 0.005;

            if (copied) {
                multipliers.add(new ScoreMultiplier(
                        -0.05,
                        "抄袭惩罚"
                ));
            }
        }

        return new GuessResult(previousGuess == null ? GuessStatus.RECORDED : GuessStatus.UPDATED, guess, multipliers);
    }

    public synchronized EndResult end(String groupId, String senderUserId, boolean admin) {
        Game game = games.get(groupId);
        if (game == null) {
            return new EndResult(EndStatus.NO_GAME, null);
        }
        if (game.round == null) {
            return new EndResult(EndStatus.STARTING, null);
        }
        if (clock.instant().isBefore(game.guessingStartedAt.plus(END_PROTECTION_DURATION))
                && !Objects.equals(game.starterUserId, senderUserId)
                && !admin) {
            return new EndResult(EndStatus.FORBIDDEN, null);
        }

        games.remove(groupId);
        List<Standing> standings = new ArrayList<>(game.guesses.size());
        for (Map.Entry<String, Guess> entry : game.guesses.entrySet()) {
            Guess guess = entry.getValue();
            double error = logarithmicError(guess.rank(), game.round.actualRank());
            standings.add(new Standing(
                    entry.getKey(),
                    guess.rank(),
                    Math.max(0, 1000 * (1 - error)),
                    game.round.actualRank() - guess.rank(),
                    error,
                    guess.sequence()
            ));
        }
        standings.sort(Comparator
                .comparingDouble(Standing::points).reversed()
                .thenComparingDouble(Standing::error)
                .thenComparingLong(Standing::sequence));

        return new EndResult(
                EndStatus.FINISHED,
                new FinishedRound(game.round, List.copyOf(standings))
        );
    }

    static double logarithmicError(long guess, long actualRank) {
        return Math.abs(Math.log10(guess) - Math.log10(actualRank));
    }

    public record Reservation(String groupId, UUID token) {
    }

    public enum GuessStatus {
        NO_GAME,
        STARTING,
        RECORDED,
        UPDATED
    }

    public record ScoreMultiplier(double multiplier, String reason){}

    public record GuessResult(GuessStatus status, long rank, List<ScoreMultiplier> multipliers) {
        public String getMultipliersString() {
            if (multipliers == null || multipliers.isEmpty()) {
                return "倍率: `x1.00`\n";
            }

            StringBuilder builder = new StringBuilder();

            double sum = multipliers.stream()
                    .mapToDouble(ScoreMultiplier::multiplier)
                    .sum();

            builder.append("倍率: `x")
                    .append(String.format(Locale.US, "%.2f", 1 + sum))
                    .append("`\n");

            for (ScoreMultiplier multiplier : multipliers) {
                builder.append("> ")
                        .append(multiplier.reason())
                        .append(": ")
                        .append(String.format(
                                Locale.US,
                                "%+.0f%%",
                                multiplier.multiplier() * 100
                        ))
                        .append("\n");
            }

            return builder.toString();
        }
    }

    public enum EndStatus {
        NO_GAME,
        STARTING,
        FORBIDDEN,
        FINISHED
    }

    public record EndResult(EndStatus status, FinishedRound round) {
    }

    public record Round(long userId, long scoreId, long actualRank, Double pp) {
        public Round {
            if (userId <= 0 || scoreId <= 0 || actualRank <= 0) {
                throw new IllegalArgumentException("Rank Guess 数据必须包含有效的用户、成绩和排名");
            }
        }

        public static Round from(RandomScore randomScore) {
            if (randomScore == null || randomScore.user() == null || randomScore.score() == null) {
                throw new IllegalArgumentException("随机成绩响应缺少用户或成绩数据");
            }
            if (randomScore.user().getStatistics() == null
                    || randomScore.user().getStatistics().getGlobalRank() == null
                    || randomScore.user().getStatistics().getGlobalRank() <= 0) {
                throw new IllegalArgumentException("随机用户缺少有效的全球排名");
            }
            if (randomScore.score().getId() == null || randomScore.score().getId() <= 0) {
                throw new IllegalArgumentException("随机成绩缺少有效的成绩ID");
            }
            if (randomScore.user().getId() <= 0) {
                throw new IllegalArgumentException("随机用户缺少有效的用户ID");
            }

            return new Round(
                    randomScore.user().getId(),
                    randomScore.score().getId(),
                    randomScore.user().getStatistics().getGlobalRank(),
                    randomScore.score().getPp()
            );
        }
    }

    public record Standing(
            String senderUserId,
            long guess,
            double points,
            long delta,
            double error,
            long sequence
    ) {
    }

    public record FinishedRound(Round round, List<Standing> standings) {
    }

    private static final class Game {
        private final UUID token;
        private final String starterUserId;
        private final Map<String, Guess> guesses = new LinkedHashMap<>();
        private final AtomicInteger guessCount = new AtomicInteger(0);
        private Round round;
        private Instant guessingStartedAt;
        private long nextSequence;

        private Game(UUID token, String starterUserId) {
            this.token = token;
            this.starterUserId = starterUserId;
        }
    }

    private record Guess(long rank, long sequence) {
    }
}
