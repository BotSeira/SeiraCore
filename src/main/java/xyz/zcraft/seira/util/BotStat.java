package xyz.zcraft.seira.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public class BotStat {
    private static final Path STAT_FILE = Path.of("data", "bot-stat.json");
    private static final Logger LOG = LogManager.getLogger(BotStat.class);
    private final AtomicLong totalCommands;
    private final AtomicLong totalReplays;
    private final AtomicLong totalUptime;

    private final long startTime;

    public BotStat() {
        startTime = System.currentTimeMillis();
        if (Files.exists(STAT_FILE)) {
            JsonObject obj = null;
            try {
                obj = JsonParser.parseString(Files.readString(STAT_FILE)).getAsJsonObject();
            } catch (IOException e) {
                LOG.warn("Failed to load stat file, defaulting to 0", e);
            }

            if (obj != null) {
                this.totalCommands = new AtomicLong(getNum(obj, "total-commands"));
                this.totalReplays = new AtomicLong(getNum(obj, "total-replays"));
                this.totalUptime = new AtomicLong(getNum(obj, "total-uptime"));
                return;
            }
        }

        this.totalCommands = new AtomicLong(0);
        this.totalReplays = new AtomicLong(0);
        this.totalUptime = new AtomicLong(0);
    }

    private long getNum(JsonObject obj, String key) {
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

    public void incrementCommands() {
        totalCommands.incrementAndGet();
    }

    public void incrementReplays() {
        totalReplays.incrementAndGet();
    }

    public long getTotalCommands() {
        return totalCommands.get();
    }

    public long getTotalReplays() {
        return totalReplays.get();
    }

    public long getTotalUptime() {
        return totalUptime.get() + getCurrentUptime();
    }

    public long getCurrentUptime() {
        return System.currentTimeMillis() - startTime;
    }

    public void saveToFile() {
        JsonObject obj = new JsonObject();
        obj.addProperty("total-commands", totalCommands.get());
        obj.addProperty("total-replays", totalReplays.get());
        obj.addProperty("total-uptime", totalUptime.get() + (System.currentTimeMillis() - startTime));

        try {
            Files.writeString(STAT_FILE, obj.toString());
        } catch (IOException e) {
            LOG.error("Failed to write bot stat to file", e);
        }
    }
}
