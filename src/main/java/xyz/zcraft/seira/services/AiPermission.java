package xyz.zcraft.seira.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class AiPermission {
    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE = Path.of("data", "ai-permission.json");
    private static Set<String> groups = null;
    @Getter
    private static Mode mode = Mode.WHITELIST;
    private static Set<String> activated = null;
    private static boolean useLegacy = false;

    public static synchronized void initialize() {
        loadFromFile();
    }

    public static synchronized void loadFromFile() {
        synchronized (LOCK) {
            try {
                if (Files.exists(STORE)) {
                    String json = Files.readString(STORE);

                    if (!json.isBlank()) {
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

                        final PermissionSnapshot snapshot = GSON.fromJson(obj, PermissionSnapshot.class);

                        AiPermission.useLegacy = snapshot.useLegacy;
                        AiPermission.mode = snapshot.mode;
                        AiPermission.groups = new HashSet<>();
                        AiPermission.activated = new HashSet<>();

                        final Set<String> groups = snapshot.groups;
                        if (groups != null) {
                            AiPermission.groups.addAll(groups);
                        }

                        final Set<String> activated = snapshot.activated;
                        if (activated != null) {
                            AiPermission.activated.addAll(activated);
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

        }
    }

    public static synchronized void saveToFile() {
        synchronized (LOCK) {
            try {
                Files.createDirectories(STORE.getParent());

                PermissionSnapshot snapshot = new PermissionSnapshot(useLegacy, mode, groups, activated);

                Files.writeString(STORE, GSON.toJson(snapshot));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load ai permission", e);
            }
        }
    }

    public static synchronized boolean permits(String groupId) {
        if (mode == null || groups == null) return false;

        if (mode == Mode.WHITELIST) {
            return groups.contains(groupId);
        } else if (mode == Mode.BLACKLIST) {
            return !groups.contains(groupId);
        } else {
            return false;
        }
    }

    public static synchronized void grant(String groupId) {
        if (mode == null || groups == null) return;

        if (mode == Mode.WHITELIST) {
            groups.add(groupId);
        } else if (mode == Mode.BLACKLIST) {
            groups.remove(groupId);
        }

        saveToFile();
    }

    public static synchronized void revoke(String groupId) {
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

    public static synchronized void activate(String groupId) {
        if (!permits(groupId)) throw new IllegalStateException("AI is not authorized for this group");
        if (activated == null) return;
        activated.add(groupId);

        saveToFile();
    }

    public static synchronized void deactivate(String groupId) {
        if (activated == null) return;
        activated.remove(groupId);

        saveToFile();
    }

    public static synchronized boolean isActivated(String groupId) {
        if (activated == null) return false;
        return permits(groupId) && activated.contains(groupId);
    }

    public static synchronized boolean doUseLegacy() {
        return useLegacy;
    }

    public static synchronized void setUseLegacy(boolean value) {
        useLegacy = value;
        saveToFile();
    }

    public enum Mode {
        WHITELIST,
        BLACKLIST
    }

    public record PermissionSnapshot(
            boolean useLegacy,
            Mode mode,
            Set<String> groups,
            Set<String> activated
    ) {
    }
}
