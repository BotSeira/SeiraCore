package xyz.zcraft.seira.rankguess;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.seira.bot.data.MessageReference;
import xyz.zcraft.seira.db.RankGuessRecordStore;
import xyz.zcraft.seira.rankguess.data.*;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static xyz.zcraft.seira.rankguess.RankGuessGame.COPY_PUNISHMENT_THRESHOLD;

public final class RankGuessGameService {
    public static final int SCORING_VERSION = 1;
    public static final int MIN_GAMES_TO_RANK = 10;
    private static final Duration END_PROTECTION_DURATION = Duration.ofMinutes(3);
    private static final int MIN_PARTICIPANT_TO_RECORD = 3;
    private final Map<String, RankGuessGame> games = new HashMap<>();
    private final RankGuessWeights weights;
    private final Clock clock;
    private final Consumer<FinishedRound> recordWriter;

    public RankGuessGameService() {
        this(Clock.systemUTC());
    }

    RankGuessGameService(Clock clock) {
        this(clock, new RankGuessWeights());
    }

    RankGuessGameService(Clock clock, RankGuessWeights weights) {
        this(clock, weights, RankGuessRecordStore::save);
    }

    RankGuessGameService(Clock clock, RankGuessWeights weights, Consumer<FinishedRound> recordWriter) {
        this.clock = clock;
        this.weights = weights;
        this.recordWriter = Objects.requireNonNull(recordWriter);
    }

    static double logarithmicError(long guess, long actualRank) {
        return Math.abs(Math.log10(guess) - Math.log10(actualRank));
    }

    @NotNull
    public static String getGlobalRankRange(long l) {
        String range;

        if (l <= 10_000) {
            range = "#1 - #10k";
        } else if (l <= 50_000) {
            range = "#10k - #50k";
        } else if (l <= 200_000) {
            range = "#50k - #200k";
        } else if (l <= 500_000) {
            range = "#200k - #500k";
        } else {
            range = ">#500k";
        }

        return range;
    }

    public void saveWeights() {
        weights.saveToFile();
    }

    public JsonObject generateWeights(String groupId) {
        return weights.generateWeights(groupId);
    }

    public synchronized Reservation reserve(String groupId, String starterUserId, boolean fromGroup) {
        if (games.containsKey(groupId)) {
            return null;
        }

        Reservation reservation = new Reservation(groupId, UUID.randomUUID());
        games.put(groupId, new RankGuessGame(reservation.token(), starterUserId, fromGroup));
        return reservation;
    }

    public synchronized RankGuessGame activate(Reservation reservation, Round round, MessageReference videoRef) {
        if (round == null) {
            throw new IllegalArgumentException("Round must not be null");
        }
        RankGuessGame game = games.get(reservation.groupId());
        if (game == null || !game.token.equals(reservation.token())) {
            return null;
        }

        game.round = round;
        game.videoRef = videoRef;
        game.guessingStartedAt = clock.instant();

        return game;
    }

    public synchronized void cancel(Reservation reservation) {
        RankGuessGame game = games.get(reservation.groupId());
        if (game != null && game.token.equals(reservation.token())) {
            games.remove(reservation.groupId());
        }
    }

    public synchronized Set<String> activeGroupIds() {
        return Set.copyOf(games.keySet());
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

        final Guess previousGuess = game.guesses.get(senderUserId);

        if (previousGuess != null) {
            final long l = (System.currentTimeMillis() - previousGuess.timestamp()) / 1000;

            if (l < 20) {
                return GuessResponse.ofStatus(GuessStatus.TOO_SOON);
            }
        }

        final int guessNumber = game.guessCount.incrementAndGet();

        LinkedList<ScoreMultiplier> multipliers = new LinkedList<>();

        for (RankGuessGame.Hint hint : game.getRevealedHints()) {
            multipliers.add(new ScoreMultiplier.HintMultiplier(hint));
        }

        if (guessNumber == 1) {
            multipliers.add(new ScoreMultiplier.FirstGuessMultiplier());
        } else {
            multipliers.add(new ScoreMultiplier.OrderMultiplier(guessNumber));
        }

        String message = null;

        List<Guess> otherGuesses = game.guesses.entrySet().stream()
                .filter(entry -> !Objects.equals(entry.getKey(), senderUserId))
                .map(Map.Entry::getValue)
                .toList();

        long closestGuess = otherGuesses.stream()
                .mapToLong(Guess::rank)
                .boxed()
                .min(Comparator.comparingLong(previous -> Math.abs(previous - guess)))
                .orElse(-1L);

        if (closestGuess != -1) {
            double absoluteDifference = Math.abs(closestGuess - guess);
            double relativeDifference = absoluteDifference / (double) Math.max(closestGuess, guess);

            boolean copied =
                    (absoluteDifference <= 100 && relativeDifference <= 0.01)
                            || Math.abs(Math.log10(closestGuess) - Math.log10(guess)) < 0.005;

            if (copied) {
                multipliers.add(new ScoreMultiplier.CopyPunishmentMultiplier());
            }
        }

        game.guesses.put(senderUserId, Guess.of(guess, game.nextSequence++, multipliers));

        final int i = game.guesses.size();

        if (i >= COPY_PUNISHMENT_THRESHOLD && !game.copyPunishmentReduced) {
            game.copyPunishmentReduced = true;
            message = "提示：由于本次游戏参与人数较多，所有猜测的抄袭惩罚已降至 `-2.5%` ~";
        }

        final GuessResult guessResult = new GuessResult(
                previousGuess == null ? GuessStatus.RECORDED : GuessStatus.UPDATED,
                guess,
                multipliers,
                game.getMultipliersString(multipliers),
                game.guesses.size()
        );

        return GuessResponse.of(game, guessResult, message);
    }

    public synchronized EndResult end(String groupId, String senderUserId, boolean admin, boolean force) {
        RankGuessGame game = games.get(groupId);
        if (game == null) {
            return new EndResult(EndResult.EndStatus.NO_GAME, null, null);
        }
        if (game.round == null) {
            return new EndResult(EndResult.EndStatus.STARTING, null, null);
        }
        if (!force
                && clock.instant().isBefore(game.guessingStartedAt.plus(END_PROTECTION_DURATION))
                && !Objects.equals(game.starterUserId, senderUserId)
                && !admin) {
            return new EndResult(EndResult.EndStatus.FORBIDDEN, null, null);
        }

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
                .comparingDouble(Standing::points).reversed()
                .thenComparingDouble(Standing::error)
                .thenComparingLong(Standing::sequence));

        FinishedRound finished = new FinishedRound(
                game.token, groupId, game.fromGroup, game.guessingStartedAt, clock.instant(),
                SCORING_VERSION, game.round, standings
        );

        EndResult.RankType rankType = EndResult.RankType.RANKED;

        if (game.guesses.size() < MIN_PARTICIPANT_TO_RECORD) {
            rankType = EndResult.RankType.NOT_ENOUGH_PARTICIPANT;
        }

        if (!game.round.standard()) {
            rankType = EndResult.RankType.NOT_A_STANDARD_GAME;
        }

        if (rankType == EndResult.RankType.RANKED) {
            recordWriter.accept(finished);
        }

        games.remove(groupId);
        game.markEnded();
        weights.recordRound(groupId, game.round.userId(), game.round.scoreId());

        return new EndResult(
                EndResult.EndStatus.FINISHED,
                finished,
                rankType
        );
    }

    public WishResult wish(String groupId, Long boundUid) {
        return weights.tryWish(groupId, boundUid);
    }

    public void stopAll() {
        Map.copyOf(games).forEach((s, g) -> {
            if (g.isEnded()) return;

            end(s, null, true, true);
        });
    }

    public GameStatus getStatus(String s) {
        final RankGuessGame rankGuessGame = games.get(s);
        if (rankGuessGame == null) {
            return GameStatus.NO_GAME;
        }
        if (rankGuessGame.getRound() == null) {
            return GameStatus.STARTING;
        }
        return GameStatus.RUNNING;
    }

    public MessageReference getVideoMessageRef(String s) {
        final RankGuessGame rankGuessGame = games.get(s);
        if (rankGuessGame == null || rankGuessGame.getRound() == null || rankGuessGame.getVideoRef() == null) {
            return null;
        }

        return rankGuessGame.getVideoRef();
    }

    public int getParticipantCount(String groupId) {
        final RankGuessGame rankGuessGame = games.get(groupId);
        if (rankGuessGame == null) {
            return 0;
        }
        return rankGuessGame.guesses.size();
    }

    public RankGuessWeights.Probability getProbabilityFor(String groupId, Long boundUid) {
        return weights.getProbability(groupId, boundUid);
    }

    public enum GameStatus {
        NO_GAME,
        STARTING,
        RUNNING
    }

    public enum GuessStatus {
        NO_GAME,
        STARTING,
        RECORDED,
        TOO_SOON,
        UPDATED
    }

    public enum WishResult {
        SUCCESS,
        ALREADY_WISHED,
        RECENTLY_PICKED
    }
}
