package xyz.zcraft.seira.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class AiPermission {
    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE = Path.of("data", "ai-permission.json");
    private static Set<String> groups = null;
    @Getter
    private static Mode mode = Mode.WHITELIST;

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

                        final PermissionSnapshot permissionSnapshot = GSON.fromJson(obj, PermissionSnapshot.class);

                        mode = permissionSnapshot.mode;
                        groups = new HashSet<>();

                        final Set<String> groups = permissionSnapshot.groups;
                        if (groups != null) {
                            AiPermission.groups.addAll(groups);
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
        }
    }

    public static void saveToFile() {
        synchronized (LOCK) {
            try {
                Files.createDirectories(STORE.getParent());

                PermissionSnapshot snapshot = new PermissionSnapshot(mode, groups);

                Files.writeString(STORE, GSON.toJson(snapshot));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load ai permission", e);
            }
        }
    }

    public static boolean doPermit(String groupId) {
        if (mode == null || groups == null) return false;

        if (mode == Mode.WHITELIST) {
            return groups.contains(groupId);
        } else if (mode == Mode.BLACKLIST) {
            return !groups.contains(groupId);
        } else {
            return false;
        }
    }

    public static void permit(String groupId) {
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

        saveToFile();
    }

    public enum Mode {
        WHITELIST,
        BLACKLIST
    }

    public record PermissionSnapshot(
            Mode mode,
            Set<String> groups
    ) {
    }
}
