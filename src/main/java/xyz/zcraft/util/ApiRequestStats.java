package xyz.zcraft.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

public class ApiRequestStats {
    private static final int WINDOW_SIZE = 10;
    private static final long DEFAULT_COST_MILLIS = 10000L;

    private final ConcurrentHashMap<String, StatsEntry> statsByType = new ConcurrentHashMap<>();

    public long estimateAndEnqueue(String requestType) {
        StatsEntry entry = statsByType.computeIfAbsent(requestType, ignored -> new StatsEntry());
        synchronized (entry) {
            long avgMillis = entry.samples.isEmpty()
                    ? DEFAULT_COST_MILLIS
                    : (long) entry.samples.stream().mapToLong(Long::longValue).average().orElse(DEFAULT_COST_MILLIS);
            entry.pendingCount++;
            // TODO THIS DON'T SEEM TO BE VERY ACCURATE, CHANGE IT IN THE FUTURE...
            long expectedMillis = avgMillis * Math.min(entry.pendingCount, WINDOW_SIZE);
            return Math.max(1L, (expectedMillis + 999L) / 1000L);
        }
    }

    public void complete(String requestType, long elapsedMillis) {
        StatsEntry entry = statsByType.computeIfAbsent(requestType, ignored -> new StatsEntry());
        synchronized (entry) {
            entry.pendingCount = Math.max(0, entry.pendingCount - 1);
            entry.samples.addLast(Math.max(1L, elapsedMillis));
            while (entry.samples.size() > WINDOW_SIZE) {
                entry.samples.removeFirst();
            }
        }
    }

    private static class StatsEntry {
        private final Deque<Long> samples = new ArrayDeque<>();
        private int pendingCount;
    }
}

