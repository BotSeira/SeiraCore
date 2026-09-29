package xyz.zcraft.seira.util;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

public class WeightedRandom<T> {
    private final List<T> items = new ArrayList<>();
    private final List<Double> cumulative = new ArrayList<>();

    public void add(T item, double weight) {
        if (weight <= 0 || Double.isNaN(weight) || Double.isInfinite(weight)) {
            throw new IllegalArgumentException("Weight must be positive and finite");
        }

        items.add(item);

        double previous = cumulative.isEmpty() ? 0.0 : cumulative.getLast();

        cumulative.add(previous + weight);
    }

    public T next() {
        return items.get(randomIndex());
    }

    public T getAndRemove() {
        int index = randomIndex();

        T result = items.remove(index);

        double previous = index == 0 ? 0.0 : cumulative.get(index - 1);

        double removedWeight = cumulative.get(index) - previous;

        cumulative.remove(index);

        for (int i = index; i < cumulative.size(); i++) {
            cumulative.set(i, cumulative.get(i) - removedWeight);
        }

        return result;
    }

    private int randomIndex() {
        if (items.isEmpty()) {
            throw new NoSuchElementException("WeightedRandom is empty");
        }

        double total = cumulative.getLast();
        double random = ThreadLocalRandom.current().nextDouble(total);

        for (int i = 0; i < cumulative.size(); i++) {
            if (random < cumulative.get(i)) {
                return i;
            }
        }

        throw new IllegalStateException();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }
}