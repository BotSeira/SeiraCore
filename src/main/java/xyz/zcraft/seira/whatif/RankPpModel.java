package xyz.zcraft.seira.whatif;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Monotone piecewise linear fit in log(rank), log(pp), with the same inverse.
 */
public final class RankPpModel {
    private final List<Sample> samples;

    public RankPpModel(List<Sample> input) {
        if (input == null) throw new IllegalArgumentException("缺少排名样本。");
        var sorted = input.stream().filter(s -> s != null && s.valid())
                .sorted(Comparator.comparingLong(Sample::rank).thenComparing(Comparator.comparingDouble(Sample::pp).reversed()))
                .toList();
        var monotone = new ArrayList<Sample>();
        for (Sample sample : sorted) {
            if (monotone.isEmpty() || (sample.rank() > monotone.getLast().rank()
                    && sample.pp() < monotone.getLast().pp())) {
                monotone.add(sample);
            }
        }
        if (monotone.size() < 2) throw new IllegalArgumentException("有效排名样本不足。");
        this.samples = List.copyOf(monotone);
    }

    private static double interpolate(double x, double x1, double x2, double y1, double y2) {
        if (x == x1) return y1;
        if (x == x2) return y2;
        double weight = (Math.log(x) - Math.log(x1)) / (Math.log(x2) - Math.log(x1));
        return Math.exp(Math.log(y1) + weight * (Math.log(y2) - Math.log(y1)));
    }

    public List<Sample> samples() {
        return samples;
    }

    public Sample first() {
        return samples.getFirst();
    }

    public Sample last() {
        return samples.getLast();
    }

    public double ppAtRank(long rank) {
        if (rank < first().rank() || rank > last().rank()) {
            throw new IllegalArgumentException("排名超出样本范围。");
        }
        int low = 1, high = samples.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (samples.get(mid).rank() < rank) low = mid + 1;
            else high = mid;
        }
        Sample a = samples.get(low - 1), b = samples.get(low);
        return interpolate(rank, a.rank(), b.rank(), a.pp(), b.pp());
    }

    public double rankAtPp(double pp) {
        if (!Double.isFinite(pp) || pp < last().pp() || pp > first().pp()) {
            throw new IllegalArgumentException("PP 超出样本范围。");
        }
        int low = 1, high = samples.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (samples.get(mid).pp() > pp) low = mid + 1;
            else high = mid;
        }
        Sample a = samples.get(low - 1), b = samples.get(low);
        return interpolate(pp, a.pp(), b.pp(), a.rank(), b.rank());
    }

    public record Sample(long userId, long rank, double pp, long observedAt) {
        public Sample(long userId, long rank, double pp) {
            this(userId, rank, pp, 0);
        }

        public boolean valid() {
            return userId > 0 && rank > 0 && Double.isFinite(pp) && pp > 0;
        }
    }
}
