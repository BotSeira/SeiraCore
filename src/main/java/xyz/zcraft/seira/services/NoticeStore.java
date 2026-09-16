package xyz.zcraft.seira.services;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.data.Notice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

public class NoticeStore {
    private static final Logger LOG = LogManager.getLogger(NoticeStore.class);
    private static final Path STORE = Path.of("data", "notices.json");
    private static final Path CONTENT_STORE = Path.of("data","notices");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static TreeSet<Notice> notices = null;

    public static void initialize() {
        loadFromFile();
    }

    public static long createNoticeDraft() {
        synchronized (LOCK) {
            ensureInitialized();

            final long newId = notices.stream().mapToLong(Notice::id).max().orElse(0L) + 1;

            notices.add(
                    new Notice(newId, "", "", false,
                            Notice.NoticeLevel.INFO, null, 60L)
            );

            saveToFile();

            return newId;
        }
    }

    public static String getContentFor(Notice notice) {
        synchronized (LOCK) {
            if (notice == null ||
                    notice.fileName() == null ||
                    notice.fileName().isBlank()) {
                return null;
            }
            try {
                return Files.readString(CONTENT_STORE.resolve(notice.fileName()));
            } catch (IOException e) {
                LOG.error("Failed to read notice content", e);
                throw new RuntimeException(e);
            }
        }
    }

    private static void ensureInitialized() {
        if (notices == null) {
            throw new IllegalStateException("NoticeStore is not initialized");
        }
    }

    public static int loadFromFile() {
        synchronized (LOCK) {
            try {
                TreeSet<Notice> loaded = new TreeSet<>();

                if (Files.exists(STORE)) {
                    String json = Files.readString(STORE);

                    if (!json.isBlank()) {
                        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();

                        for (JsonElement elem : arr) {
                            loaded.add(
                                    GSON.fromJson(elem, Notice.class)
                            );
                        }
                    }
                }

                notices = loaded;
                return loaded.size();

            } catch (Exception e) {
                throw new RuntimeException("Failed to load notice store", e);
            }
        }
    }

    public static void saveToFile() {
        synchronized (LOCK) {
            if (notices == null) {
                return;
            }

            try {
                String json = GSON.toJson(notices);
                Files.writeString(
                        STORE,
                        json,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } catch (IOException e) {
                LOG.error("Failed to save notices to file", e);
                throw new RuntimeException("Failed to save notices", e);
            }
        }
    }

    public static Set<Notice> getNotices() {
        synchronized (LOCK) {
            ensureInitialized();
            loadFromFile();
            return Set.copyOf(notices);
        }
    }

    public static long getNewestId() {
        synchronized (LOCK) {
            ensureInitialized();
            loadFromFile();

            return notices.stream()
                    .filter(Notice::isActive)
                    .mapToLong(Notice::id)
                    .max()
                    .orElse(0L);
        }
    }

    public static Notice getNewestNotice() {
        synchronized (LOCK) {
            ensureInitialized();
            loadFromFile();

            return notices.stream()
                    .filter(Notice::isActive)
                    .max(Comparator.comparingLong(Notice::id))
                    .orElse(null);
        }
    }

    public static boolean publish(long id) {
        synchronized (LOCK) {
            ensureInitialized();
            loadFromFile();

            Notice notice = notices.stream()
                    .filter(n -> n.id() == id)
                    .findFirst()
                    .orElse(null);

            if (notice == null || notice.published() || !notice.valid()) {
                return false;
            }
            notices.remove(notice);

            notices.add(new Notice(
                    notice.id(),
                    notice.title(),
                    notice.fileName(),
                    true,
                    notice.level(),
                    System.currentTimeMillis(),
                    notice.expiresInMinutes()
            ));

            saveToFile();

            return true;
        }
    }

    public static boolean revoke(long id) {
        synchronized (LOCK) {
            ensureInitialized();
            loadFromFile();

            Notice notice = notices.stream()
                    .filter(n -> n.id() == id)
                    .findFirst()
                    .orElse(null);

            if (notice == null || !notice.published()) {
                return false;
            }

            notices.remove(notice);

            notices.add(new Notice(
                    notice.id(),
                    notice.title(),
                    notice.fileName(),
                    false,
                    notice.level(),
                    System.currentTimeMillis(),
                    notice.expiresInMinutes()
            ));

            saveToFile();

            return true;
        }
    }
}
