package xyz.zcraft.seira.rankguess;

import lombok.Getter;
import xyz.zcraft.seira.api.data.RandomScore;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

public final class RankGuessGameService {
    private static final Duration END_PROTECTION_DURATION = Duration.ofMinutes(3);

    private final Map<String, RankGuessGame> games = new HashMap<>();
    private final Clock clock;

    public RankGuessGameService() {
        this(Clock.systemUTC());
    }

    RankGuessGameService(Clock clock) {
        this.clock = clock;
    }

    static double logarithmicError(long guess, long actualRank) {
        return Math.abs(Math.log10(guess) - Math.log10(actualRank));
    }

    public synchronized Reservation reserve(String groupId, String starterUserId) {
        if (games.containsKey(groupId)) {
            return null;
        }

        Reservation reservation = new Reservation(groupId, UUID.randomUUID());
        games.put(groupId, new RankGuessGame(reservation.token(), starterUserId));
        return reservation;
    }

    public synchronized RankGuessGame activate(Reservation reservation, Round round) {
        if (round == null) {
            throw new IllegalArgumentException("Round must not be null");
        }
        RankGuessGame game = games.get(reservation.groupId());
        if (game == null || !game.token.equals(reservation.token())) {
            return null;
        }

        game.round = round;
        game.guessingStartedAt = clock.instant();

        return game;
    }

    public synchronized void cancel(Reservation reservation) {
        RankGuessGame game = games.get(reservation.groupId());
        if (game != null && game.token.equals(reservation.token())) {
            games.remove(reservation.groupId());
        }
    }

    public synchronized GuessResponse guess(String groupId, String senderUserId, long guess) {
        if (guess <= 0) {
            throw new IllegalArgumentException("Rank must be positive");
        }

        RankGuessGame game = games.get(groupId);
        if (game == null) {
            return GuessResponse.ofStatus(GuessStatus.NO_GAME);
        }
        if (game.round == null) {
            return GuessResponse.ofStatus(GuessStatus.STARTING);
        }

        final int guessNumber = game.guessCount.incrementAndGet();

        LinkedList<ScoreMultiplier> multipliers = new LinkedList<>();

        if (guessNumber == 1) {
            multipliers.add(new ScoreMultiplier.FirstGuessMultiplier());
        } else {
            multipliers.add(new ScoreMultiplier.OrderMultiplier(guessNumber));
        }

        String message = null;

        long closestGuess = Arrays.stream(
                        game.guesses.values().stream()
                                .mapToLong(Guess::rank)
                                .toArray()
                ).boxed()
                .min(Comparator.comparingLong(previous -> Math.abs(previous - guess)))
                .orElse(-1L);

        if (closestGuess != -1) {
            double absoluteDifference = Math.abs(closestGuess - guess);
            double relativeDifference = absoluteDifference
                    / (double) Math.max(closestGuess, guess);

            boolean copied =
                    (absoluteDifference <= 100 && relativeDifference <= 0.01)
                            || Math.abs(
                            Math.log10(closestGuess)
                                    - Math.log10(guess)
                    ) < 0.005;

            final int i = game.guessCount.get();

            if (i >= 10) {
                message = "提示：由于本次游戏参与人数较多，所有猜测的抄袭惩罚已降至 `-2.5%` ~";
            }

            if (copied) {
                multipliers.add(new ScoreMultiplier.CopyPunishmentMultiplier());
            }
        }

        Guess previousGuess = game.guesses.put(senderUserId, new Guess(guess, game.nextSequence++, multipliers));

        final GuessResult guessResult = new GuessResult(
                previousGuess == null ? GuessStatus.RECORDED : GuessStatus.UPDATED,
                guess,
                multipliers,
                game.getMultipliersString(multipliers)
        );

        return GuessResponse.of(guessResult, message);
    }

    public synchronized EndResult end(String groupId, String senderUserId, boolean admin) {
        RankGuessGame game = games.get(groupId);
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
        game.markEnded();
        List<Standing> standings = new ArrayList<>(game.guesses.size());
        for (Map.Entry<String, Guess> entry : game.guesses.entrySet()) {
            Guess guess = entry.getValue();
            double error = logarithmicError(guess.rank(), game.round.actualRank());

            final double pointsRaw = Math.max(0, 1000 * (1 - error));

            double finalMultiplier = 1;
            for (ScoreMultiplier multiplier : guess.multipliers()) {
                finalMultiplier += game.getMultiplierDelta(multiplier);
            }

            standings.add(new Standing(
                    entry.getKey(),
                    guess.rank(),
                    pointsRaw,
                    finalMultiplier,
                    pointsRaw * finalMultiplier,
                    game.round.actualRank() - guess.rank(),
                    error,
                    guess.sequence()
            ));
        }
        standings.sort(Comparator
                .comparingDouble(Standing::pointsRaw).reversed()
                .thenComparingDouble(Standing::error)
                .thenComparingLong(Standing::sequence));

        return new EndResult(
                EndStatus.FINISHED,
                new FinishedRound(game.round, List.copyOf(standings))
        );
    }

    public enum GuessStatus {
        NO_GAME,
        STARTING,
        RECORDED,
        UPDATED
    }

    public enum EndStatus {
        NO_GAME,
        STARTING,
        FORBIDDEN,
        FINISHED
    }

    public record Reservation(String groupId, UUID token) {
    }

    public abstract static class ScoreMultiplier {
        @Getter
        private final String reason;

        protected ScoreMultiplier(String reason) {
            this.reason = reason;
        }

        static class FirstGuessMultiplier extends ScoreMultiplier {
            FirstGuessMultiplier() {
                super("首猜加成");
            }
        }

        static class OrderMultiplier extends ScoreMultiplier {
            @Getter
            private final int order;

            OrderMultiplier(int order) {
                this.order = order;
                super("第" + order + "猜");
            }
        }

        static class CopyPunishmentMultiplier extends ScoreMultiplier {
            CopyPunishmentMultiplier() {
                super("抄袭惩罚");
            }
        }
    }

    public record GuessResponse(GuessResult guessResult, String message) {
        public static GuessResponse ofStatus(GuessStatus status) {
            return new GuessResponse(new GuessResult(status, 0, null, null), null);
        }

        public static GuessResponse of(GuessResult guessResult, String message) {
            return new GuessResponse(guessResult, message);
        }
    }

    public record GuessResult(GuessStatus status, long rank, List<ScoreMultiplier> multipliers, String multiplierString) {

    }

    public record EndResult(EndStatus status, FinishedRound round) {
    }

    public record Round(long userId, long scoreId, int bestIndex, long actualRank, Double pp, RandomScore randomScore) {
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
                    randomScore.bestIndex(),
                    randomScore.user().getStatistics().getGlobalRank(),
                    randomScore.score().getPp(),
                    randomScore
            );
        }
    }
}
