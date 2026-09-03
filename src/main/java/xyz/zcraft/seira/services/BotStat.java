package xyz.zcraft.seira.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BotStat {
    private static final Path STAT_FILE = Path.of("data", "bot-stat.json");
    private static final Logger LOG = LogManager.getLogger(BotStat.class);
    private static final LinkedList<Long> commandCountHistory = new LinkedList<>();
    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static final AtomicLong totalCommands = new AtomicLong();
    private static final AtomicLong totalReplays = new AtomicLong();
    private static final AtomicLong totalUptime = new AtomicLong();
    private static ScheduledExecutorService scheduler;
    private static long startTime;

    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        startTime = System.currentTimeMillis();
        loadStatFromFile();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "bot-stat-tracker");
            thread.setDaemon(true);
            return thread;
        });
        setupTracker();
    }

    private static void loadStatFromFile() {
        if (Files.exists(STAT_FILE)) {
            JsonObject obj = null;
            try {
                obj = JsonParser.parseString(Files.readString(STAT_FILE)).getAsJsonObject();
            } catch (Exception e) {
                LOG.warn("Failed to load stat file, defaulting to 0", e);
            }

            if (obj != null) {
                totalCommands.set(getNum(obj, "total-commands"));
                totalReplays.set(getNum(obj, "total-replays"));
                totalUptime.set(getNum(obj, "total-uptime"));
                return;
            }
        }

        totalCommands.set(0);
        totalReplays.set(0);
        totalUptime.set(0);
    }

    private static void setupTracker() {
        commandCountHistory.add(totalCommands.get());

        scheduler.scheduleAtFixedRate(() -> {
            synchronized (commandCountHistory) {
                long current = totalCommands.get();
                commandCountHistory.add(current);
                if (commandCountHistory.size() > 61) {
                    commandCountHistory.removeFirst();
                }
            }
        }, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    public static long getCommandCountFor(int min) {
        synchronized (commandCountHistory) {
            if (commandCountHistory.isEmpty()) {
                return 0;
            }
            int minutes = Math.max(0, min);
            return totalCommands.get() - commandCountHistory.get(
                    Math.max(0, commandCountHistory.size() - 1 - minutes)
            );
        }
    }

    private static long getNum(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return 0;
        }
        final JsonElement element = obj.get(key);
        if (element.isJsonNull()) {
            LOG.warn("{} is null in bot-stat.json, defaulting to 0", key);
            return 0;
        } else if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            LOG.warn("{} is not a number in bot-stat.json, defaulting to 0", key);
            return 0;
        } else {
            return element.getAsLong();
        }
    }

    public static void incrementCommands() {
        totalCommands.incrementAndGet();
    }

    public static void incrementReplays() {
        totalReplays.incrementAndGet();
    }

    public static long getTotalCommands() {
        return totalCommands.get();
    }

    public static long getTotalReplays() {
        return totalReplays.get();
    }

    public static long getTotalUptime() {
        return totalUptime.get() + getCurrentUptime();
    }

    public static long getCurrentUptime() {
        return initialized.get() ? System.currentTimeMillis() - startTime : 0;
    }

    public static void saveToFile() {
        JsonObject obj = new JsonObject();
        obj.addProperty("total-commands", totalCommands.get());
        obj.addProperty("total-replays", totalReplays.get());
        obj.addProperty("total-uptime", totalUptime.get() + (System.currentTimeMillis() - startTime));

        try {
            Files.createDirectories(STAT_FILE.getParent());
            Files.writeString(STAT_FILE, obj.toString());
        } catch (IOException e) {
            LOG.error("Failed to write bot stat to file", e);
        }
    }

    public static void shutdown() {
        if (!initialized.compareAndSet(true, false)) {
            return;
        }
        saveToFile();
        ScheduledExecutorService currentScheduler = scheduler;
        scheduler = null;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        synchronized (commandCountHistory) {
            commandCountHistory.clear();
        }
    }
}
