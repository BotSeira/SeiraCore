package xyz.zcraft.seira.rankguess;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public class HintUtil {
    // TODO This is so messed up. Need to rewrite in the future.
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
                    Math.min(remaining.size() + result.size(), maxCount),
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
        double[] first = {0, 50, 35, 15, 0, 0};
        double[] middle = {0, 10, 30, 45, 15, 0};
        double[] late = {0, 0, 10, 40, 50, 0};

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
}
