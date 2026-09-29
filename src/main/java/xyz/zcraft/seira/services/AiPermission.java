package xyz.zcraft.seira.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AiPermission {
    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE = Path.of("data", "ai-permission.json");
    private static Set<String> groups = null;
    @Getter
    private static Mode mode = Mode.WHITELIST;
    private static Set<String> activated = null;
    private static Map<String, Integer> parallel = null;

    public static void initialize() {
        loadFromFile();
    }

    public static void loadFromFile() {
        synchronized (LOCK) {
            try {
                if (Files.exists(STORE)) {
                    String json = Files.readString(STORE);

                    if (!json.isBlank()) {
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

                        final PermissionSnapshot snapshot = GSON.fromJson(obj, PermissionSnapshot.class);

                        AiPermission.mode = snapshot.mode;
                        AiPermission.groups = new HashSet<>();
                        AiPermission.activated = new HashSet<>();
                        AiPermission.parallel = new HashMap<>();

                        final Set<String> groups = snapshot.groups;
                        if (groups != null) {
                            AiPermission.groups.addAll(groups);
                        }

                        final Set<String> activated = snapshot.activated;
                        if (activated != null) {
                            AiPermission.activated.addAll(activated);
                        }

                        final Map<String, Integer> parallel = snapshot.parallel;
                        if (parallel != null) {
                            AiPermission.parallel.putAll(parallel);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load ai permission", e);
            }

            if (mode == null) {
                mode = Mode.WHITELIST;
            }

            if (groups == null) {
                groups = new HashSet<>();
            }

            if (activated == null) {
                activated = new HashSet<>();
            }

            if (parallel == null) {
                parallel = new ConcurrentHashMap<>();
            }
        }
    }

    public static void saveToFile() {
        synchronized (LOCK) {
            try {
                Files.createDirectories(STORE.getParent());

                PermissionSnapshot snapshot = new PermissionSnapshot(mode, groups, activated, parallel);

                Files.writeString(STORE, GSON.toJson(snapshot));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load ai permission", e);
            }
        }
    }

    public static boolean permits(String groupId) {
        if (mode == null || groups == null) return false;

        if (mode == Mode.WHITELIST) {
            return groups.contains(groupId);
        } else if (mode == Mode.BLACKLIST) {
            return !groups.contains(groupId);
        } else {
            return false;
        }
    }

    public static void grant(String groupId) {
        if (mode == null || groups == null) return;

        if (mode == Mode.WHITELIST) {
            groups.add(groupId);
        } else if (mode == Mode.BLACKLIST) {
            groups.remove(groupId);
        }

        saveToFile();
    }

    public static void revoke(String groupId) {
        if (mode == null || groups == null) return;

        if (mode == Mode.WHITELIST) {
            groups.remove(groupId);
        } else if (mode == Mode.BLACKLIST) {
            groups.add(groupId);
        }

        if (activated != null) {
            activated.remove(groupId);
        }

        saveToFile();
    }

    public static int getParallel(String groupId) {
        if (parallel == null) return 1;
        return parallel.getOrDefault(groupId, 1);
    }

    public static void activate(String groupId) {
        if (activated == null) return;
        activated.add(groupId);

        saveToFile();
    }

    public static void deactivate(String groupId) {
        if (activated == null) return;
        activated.remove(groupId);

        saveToFile();
    }

    public static boolean isActivated(String groupId) {
        if (activated == null) return false;
        return activated.contains(groupId);
    }

    public static void setParallel(String groupId, int parallel) {
        if (AiPermission.parallel == null) return;
        AiPermission.parallel.put(groupId, parallel);

        saveToFile();
    }

    public enum Mode {
        WHITELIST,
        BLACKLIST
    }

    public record PermissionSnapshot(
            Mode mode,
            Set<String> groups,
            Set<String> activated,
            Map<String, Integer> parallel
    ) {
    }
}
