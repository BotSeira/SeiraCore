package xyz.zcraft.seira.rankguess;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.data.RandomScore;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

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
            double relativeDifference = absoluteDifference
                    / (double) Math.max(closestGuess, guess);

            boolean copied =
                    (absoluteDifference <= 100 && relativeDifference <= 0.01)
                            || Math.abs(
                            Math.log10(closestGuess)
                                    - Math.log10(guess)
                    ) < 0.005;

            final int i = game.guesses.size();

            if (i >= 10 && !game.copyPunishmentReduced) {
                game.copyPunishmentReduced = true;
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
                game.getMultipliersString(multipliers),
                game.guesses.size()
        );

        return GuessResponse.of(guessResult, message);
    }

    public static LinkedList<RankGuessGame.Hint> prepareHints(List<RankGuessGame.Hint> source, int maxCount) {
        var random = ThreadLocalRandom.current();

        List<RankGuessGame.Hint> remaining = new ArrayList<>(source);
        limitCategory(remaining, RankGuessGame.Hint.HintCategory.ACTIVITY, 2, random);

        LinkedList<RankGuessGame.Hint> result = new LinkedList<>();
        while (!remaining.isEmpty() && result.size() < maxCount) {
            List<RankGuessGame.Hint> candidates = candidatesForNextHint(remaining, result);
            RankGuessGame.Hint selected = selectWeightedByStrength(
                    candidates,
                    result.size(),
                    remaining.size() + result.size(),
                    random
            );
            result.add(selected);
            remaining.remove(selected);
        }
        return result;
    }

    private static void limitCategory(
            List<RankGuessGame.Hint> hints,
            RankGuessGame.Hint.HintCategory category,
            int maximum,
            RandomGenerator random
    ) {
        List<RankGuessGame.Hint> categoryHints = hints.stream()
                .filter(hint -> hint.category() == category)
                .toList();
        if (categoryHints.size() <= maximum) {
            return;
        }

        List<RankGuessGame.Hint> shuffled = new ArrayList<>(categoryHints);
        Collections.shuffle(shuffled, new Random(random.nextLong()));
        Set<RankGuessGame.Hint> retained = new HashSet<>(shuffled.subList(0, maximum));
        hints.removeIf(hint -> hint.category() == category && !retained.contains(hint));
    }

    private static List<RankGuessGame.Hint> candidatesForNextHint(
            List<RankGuessGame.Hint> remaining,
            List<RankGuessGame.Hint> selected
    ) {
        List<RankGuessGame.Hint> candidates = remaining;
        if (selected.isEmpty()) {
            List<RankGuessGame.Hint> nonRevealing = remaining.stream()
                    .filter(hint -> hint.strength() != RankGuessGame.Hint.HintStrength.VERY_STRONG)
                    .filter(hint -> hint.strength() != RankGuessGame.Hint.HintStrength.REVEALING)
                    .toList();
            if (!nonRevealing.isEmpty()) {
                candidates = nonRevealing;
            }
        }

        if (!selected.isEmpty()) {
            RankGuessGame.Hint.HintCategory lastCategory = selected.getLast().category();
            List<RankGuessGame.Hint> differentCategory = candidates.stream()
                    .filter(hint -> hint.category() != lastCategory)
                    .toList();
            if (!differentCategory.isEmpty()) {
                candidates = differentCategory;
            }
        }
        return candidates;
    }

    private static RankGuessGame.Hint selectWeightedByStrength(
            List<RankGuessGame.Hint> candidates,
            int position,
            int total,
            RandomGenerator random
    ) {
        double progress = total <= 1 ? 1.0 : position / (double) (total - 1);
        EnumMap<RankGuessGame.Hint.HintStrength, Double> weights = strengthWeights(progress);

        EnumMap<RankGuessGame.Hint.HintStrength, List<RankGuessGame.Hint>> byStrength =
                new EnumMap<>(RankGuessGame.Hint.HintStrength.class);
        for (RankGuessGame.Hint hint : candidates) {
            byStrength.computeIfAbsent(hint.strength(), ignored -> new ArrayList<>()).add(hint);
        }

        double totalWeight = byStrength.keySet().stream().mapToDouble(weights::get).sum();
        if (totalWeight <= 0) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        double roll = random.nextDouble(totalWeight);
        for (Map.Entry<RankGuessGame.Hint.HintStrength, List<RankGuessGame.Hint>> entry : byStrength.entrySet()) {
            roll -= weights.get(entry.getKey());
            if (roll < 0) {
                List<RankGuessGame.Hint> hints = entry.getValue();
                return hints.get(random.nextInt(hints.size()));
            }
        }
        return candidates.getLast();
    }

    private static EnumMap<RankGuessGame.Hint.HintStrength, Double> strengthWeights(double progress) {
        double[] first = {50, 35, 15, 0, 0};
        double[] middle = {25, 35, 30, 10, 0};
        double[] late = {10, 20, 35, 35, 0};
        double phase = progress <= 0.5 ? progress * 2 : (progress - 0.5) * 2;
        double[] from = progress <= 0.5 ? first : middle;
        double[] to = progress <= 0.5 ? middle : late;

        EnumMap<RankGuessGame.Hint.HintStrength, Double> weights =
                new EnumMap<>(RankGuessGame.Hint.HintStrength.class);
        RankGuessGame.Hint.HintStrength[] strengths = RankGuessGame.Hint.HintStrength.values();
        for (int i = 0; i < strengths.length; i++) {
            weights.put(strengths[i], from[i] + (to[i] - from[i]) * phase);
        }
        return weights;
    }

    public synchronized EndResult end(String groupId, String senderUserId, boolean admin, boolean force) {
        RankGuessGame game = games.get(groupId);
        if (game == null) {
            return new EndResult(EndStatus.NO_GAME, null);
        }
        if (game.round == null) {
            return new EndResult(EndStatus.STARTING, null);
        }
        if (!force
                && clock.instant().isBefore(game.guessingStartedAt.plus(END_PROTECTION_DURATION))
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
                .comparingDouble(Standing::points).reversed()
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

        static class HintMultiplier extends ScoreMultiplier {
            @Getter
            private final RankGuessGame.Hint hint;

            HintMultiplier(RankGuessGame.Hint hint) {
                super("提示 " + hint.name() + " 已揭晓");
                this.hint = Objects.requireNonNull(hint, "hint");
            }
        }
    }

    public record GuessResponse(GuessResult guessResult, String message) {
        public static GuessResponse ofStatus(GuessStatus status) {
            return new GuessResponse(new GuessResult(status, 0, null, null, 0), null);
        }

        public static GuessResponse of(GuessResult guessResult, String message) {
            return new GuessResponse(guessResult, message);
        }
    }

    public record GuessResult(GuessStatus status, long rank, List<ScoreMultiplier> multipliers, String multiplierString, int guessCount) {

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

        public LinkedList<RankGuessGame.Hint> getNormalHints() {
            LinkedList<RankGuessGame.Hint> hints = new LinkedList<>();

            final UserExtended user = this.randomScore.user();
            final Score score = this.randomScore.score();

            // ===== Rank =====

            String range = getGlobalRankRange(
                    user.getStatistics().getGlobalRank()
            );

            hints.add(new RankGuessGame.Hint(
                    "本玩家的排名范围为 `" + range + "`",
                    "排名范围",
                    RankGuessGame.Hint.HintCategory.RANK,
                    RankGuessGame.Hint.HintStrength.VERY_STRONG
            ));

            // ===== Target score =====

            hints.add(new RankGuessGame.Hint(
                    "这是此玩家的 `BP" + this.bestIndex + "`",
                    "BP位置",
                    RankGuessGame.Hint.HintCategory.TARGET_SCORE,
                    RankGuessGame.Hint.HintStrength.MEDIUM
            ));

            final long perfect = score.getStatistics().getOrDefault("great", 0L);
            final long ok = score.getStatistics().getOrDefault("ok", 0L);
            final long meh = score.getStatistics().getOrDefault("meh", 0L);
            final long miss = score.getStatistics().getOrDefault("miss", 0L);

            hints.add(new RankGuessGame.Hint(
                    "本成绩的结果为: `300: %d / 100: %d / 50: %d / Miss: %d (%.2f%%)`"
                            .formatted(
                                    perfect,
                                    ok,
                                    meh,
                                    miss,
                                    score.getAccuracy() * 100
                            ),
                    "成绩结果",
                    RankGuessGame.Hint.HintCategory.TARGET_SCORE,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));

            hints.add(new RankGuessGame.Hint(
                    "本成绩的最大连击为 `%d`".formatted(score.getMaxCombo()),
                    "成绩连击",
                    RankGuessGame.Hint.HintCategory.TARGET_SCORE,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));

//            if (score.getEndedAt() != null) {
//                hints.add(new RankGuessGame.Hint(
//                        "本成绩完成于 `%s`".formatted(score.getEndedAt()),
//                        "成绩时间",
//                        RankGuessGame.Hint.HintCategory.TARGET_SCORE,
//                        RankGuessGame.Hint.HintStrength.WEAK
//                ));
//            }

            // ===== Difficulty =====

            hints.add(new RankGuessGame.Hint(
                    "本谱面的难度为: `%s`".formatted(this.randomScore.beatmapDiff()),
                    "谱面难度",
                    RankGuessGame.Hint.HintCategory.DIFFICULTY,
                    RankGuessGame.Hint.HintStrength.STRONG
            ));

            // ===== Account activity =====

            if (user.getStatistics() != null) {
                var stats = user.getStatistics();

                hints.add(new RankGuessGame.Hint(
                        "本玩家的总游玩次数为 `%d`".formatted(stats.getPlayCount()),
                        "游玩次数",
                        RankGuessGame.Hint.HintCategory.ACTIVITY,
                        RankGuessGame.Hint.HintStrength.WEAK
                ));

                hints.add(new RankGuessGame.Hint(
                        "本玩家的总游玩时间约为 `%.0f 小时`".formatted(stats.getPlayTime() / 3600.0),
                        "游玩时间",
                        RankGuessGame.Hint.HintCategory.ACTIVITY,
                        RankGuessGame.Hint.HintStrength.WEAK
                ));

//                hints.add(new RankGuessGame.Hint(
//                        "本玩家的总命中数约为 `%,d`".formatted(stats.getTotalHits()),
//                        "总命中数",
//                        RankGuessGame.Hint.HintCategory.ACTIVITY,
//                        RankGuessGame.Hint.HintStrength.WEAK
//                ));

//                hints.add(new RankGuessGame.Hint(
//                        "本玩家的历史最大连击为 `%d`".formatted(stats.getMaximumCombo()),
//                        "最大连击",
//                        RankGuessGame.Hint.HintCategory.ACTIVITY,
//                        RankGuessGame.Hint.HintStrength.WEAK
//                ));

                hints.add(new RankGuessGame.Hint(
                        "本玩家当前等级约为 `%.1f`".formatted(stats.getLevel().getCurrent() + stats.getLevel().getProgress() / 100.0),
                        "玩家等级",
                        RankGuessGame.Hint.HintCategory.ACTIVITY,
                        RankGuessGame.Hint.HintStrength.WEAK
                ));
            }

            // ===== Account metadata =====

            if (user.getJoinDate() != null) {
                hints.add(new RankGuessGame.Hint(
                        "本玩家于 `%s` 注册 osu!".formatted(user.getJoinDate()),
                        "注册时间",
                        RankGuessGame.Hint.HintCategory.ACTIVITY,
                        RankGuessGame.Hint.HintStrength.WEAK
                ));
            }

            return hints;
        }
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
}
