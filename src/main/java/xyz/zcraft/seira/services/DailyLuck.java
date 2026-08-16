package xyz.zcraft.seira.services;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DailyLuck {
    private static final Path LUCK_FILE = Path.of("data", "daily-luck.json");
    private static final Logger LOG = LogManager.getLogger(DailyLuck.class);
    private static final ConcurrentHashMap<String, Luck> luck = new ConcurrentHashMap<>();
    private static final List<Long> ids = new LinkedList<>();
    private static final String[] FORTUNE_POOL = {
            "练底力", "练手感", "练读谱", "练爆发", "练耐力", "练速度", "练准度", "练指法", "练切分", "练长条",
            "越级", "打新图", "打旧图", "冲分", "冲FC", "冲SS", "收歌", "收BP", "挖图", "复健", "热手",
            "随机选歌", "听歌打图", "研究谱面", "研究Replay", "尝试HD", "尝试HR", "尝试DT", "打低星", "打高星",
            "打收藏夹", "清理收藏夹", "日常游玩", "长时间练习", "短时间练习", "单曲突破", "技术提升", "保持手感", "调整设备",
            "调整灵敏度", "更换皮肤", "观看比赛", "学习打法", "熬夜冲榜", "冲PP", "刷分", "赌FC",
            "打远古图", "随机谱面", "手感复健", "术曲鉴赏"
    };
    private static String salt = "Ciallo～(∠・ω< )⌒★";
    private static String luckDate;

    public static void initialize(String salt) {
        DailyLuck.salt = salt;
        loadFromFile();
    }

    public static Luck getLuck(String id) {
        synchronized (LUCK_FILE) {
            ensureUpToDate();

            if (luck.containsKey(id)) {
                return luck.get(id);
            } else {
                final Luck value = generateLuck(id);
                luck.put(id, value);

                saveToFile();

                return value;
            }
        }
    }

    private static Luck generateLuck(String id) {
        final String str = LocalDate.now().toString()
                .concat("@").concat(id)
                .concat("@").concat(salt);
        final int hash = str.hashCode();

        Random r = new Random(hash);

        int luck = r.nextInt(100) + 1;

        int up = r.nextInt(3) + 1;
        int down = r.nextInt(3) + 1;

        Set<Integer> indexes = new HashSet<>();
        while (indexes.size() < up + down) {
            indexes.add(r.nextInt(FORTUNE_POOL.length));
        }

        final String upStr = indexes.stream().limit(up).map(i -> FORTUNE_POOL[i]).reduce((a, b) -> a + ", " + b).orElse("无");
        final String downStr = indexes.stream().skip(up).map(i -> FORTUNE_POOL[i]).reduce((a, b) -> a + ", " + b).orElse("无");

        return new Luck(luck, upStr, downStr, ids.isEmpty() ? 0 : ids.get(r.nextInt(ids.size())));
    }

    private static void loadFromFile() {
        synchronized (LUCK_FILE) {
            luckDate = null;
            luck.clear();
            ids.clear();
            if (Files.exists(LUCK_FILE)) {
                JsonObject obj;
                try {
                    obj = JsonParser.parseString(Files.readString(LUCK_FILE)).getAsJsonObject();
                    luckDate = obj.get("date").getAsString();
                    obj.get("luck").getAsJsonObject().entrySet().forEach(entry -> {
                        String id = entry.getKey();
                        JsonObject luckObj = entry.getValue().getAsJsonObject();
                        luck.put(id, new Gson().fromJson(luckObj, Luck.class));
                    });
                } catch (Exception e) {
                    LOG.warn("Failed to load luck file", e);
                }

                if (luckDate == null) {
                    luckDate = LocalDate.now().toString();
                    luck.clear();
                }
            }

            try (InputStream stream = DailyLuck.class.getResourceAsStream("/beatmapset-ids.json")) {
                Objects.requireNonNull(stream, "beatmapset-ids.json resource not found");
                final JsonArray arr = JsonParser.parseString(new String(stream.readAllBytes())).getAsJsonArray();
                for (JsonElement jsonElement : arr) {
                    ids.add(jsonElement.getAsLong());
                }
            } catch (IOException e) {
                LOG.error("Failed to load beatmapset-ids.json", e);
            }
        }
    }

    public static void saveToFile() {
        synchronized (LUCK_FILE) {
            ensureUpToDate();

            JsonObject obj = new JsonObject();
            obj.addProperty("date", luckDate);
            obj.add("luck", new Gson().toJsonTree(luck));
            try {
                Files.createDirectories(LUCK_FILE.getParent());
                Files.writeString(LUCK_FILE, obj.toString());
            } catch (IOException e) {
                LOG.error("Failed to save luck file", e);
            }
        }
    }

    public static void ensureUpToDate() {
        synchronized (LUCK_FILE) {
            final String currentDate = LocalDate.now().toString();
            if (currentDate.equals(luckDate)) {
                return;
            }

            luckDate = currentDate;
            luck.clear();
        }
    }

    public record Luck(int luck, String ups, String downs, long dailyMapset) {
    }
}
