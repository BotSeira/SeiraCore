package xyz.zcraft.seira.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Combines administrators declared in config with administrators added from the console. */
public final class AdminRegistry {
    public static final Path DEFAULT_STORE = Path.of("data", "console-admins.txt");

    private static final Logger LOG = LogManager.getLogger(AdminRegistry.class);

    private final Path store;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Set<String> configured = Set.of();
    private final Set<String> persisted = new LinkedHashSet<>();

    public AdminRegistry(Collection<String> configured) {
        this(configured, DEFAULT_STORE);
    }

    public AdminRegistry(Collection<String> configured, Path store) {
        this.store = store.toAbsolutePath().normalize();
        replaceConfigured(configured);
        loadPersisted();
    }

    public boolean isAdmin(String openId) {
        if (openId == null || openId.isBlank()) {
            return false;
        }
        lock.readLock().lock();
        try {
            return configured.contains(openId) || persisted.contains(openId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public AddResult add(String openId) {
        String normalized = validate(openId);
        lock.writeLock().lock();
        try {
            if (configured.contains(normalized) || persisted.contains(normalized)) {
                return AddResult.ALREADY_PRESENT;
            }
            persisted.add(normalized);
            try {
                savePersisted();
            } catch (RuntimeException e) {
                persisted.remove(normalized);
                throw e;
            }
            return AddResult.ADDED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public RemoveResult remove(String openId) {
        String normalized = validate(openId);
        lock.writeLock().lock();
        try {
            if (configured.contains(normalized)) {
                return RemoveResult.CONFIGURED;
            }
            if (!persisted.remove(normalized)) {
                return RemoveResult.NOT_FOUND;
            }
            try {
                savePersisted();
            } catch (RuntimeException e) {
                persisted.add(normalized);
                throw e;
            }
            return RemoveResult.REMOVED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void replaceConfigured(Collection<String> adminIds) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (adminIds != null) {
            adminIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(values::add);
        }
        lock.writeLock().lock();
        try {
            configured = Set.copyOf(values);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AdminView> list() {
        lock.readLock().lock();
        try {
            LinkedHashSet<String> ids = new LinkedHashSet<>(configured);
            ids.addAll(persisted);
            return ids.stream().sorted().map(id -> new AdminView(
                    id,
                    configured.contains(id),
                    persisted.contains(id)
            )).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void loadPersisted() {
        lock.writeLock().lock();
        try {
            if (!Files.exists(store)) {
                return;
            }
            for (String line : Files.readAllLines(store)) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    persisted.add(validate(value));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load console administrators from " + store, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void savePersisted() {
        try {
            Path parent = store.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = store.resolveSibling(store.getFileName() + ".tmp");
            Files.write(temporary, persisted.stream().sorted().toList());
            try {
                Files.move(temporary, store, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, store, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Failed to persist console administrators", e);
            throw new IllegalStateException("Failed to persist console administrators", e);
        }
    }

    private static String validate(String openId) {
        if (openId == null || openId.isBlank()) {
            throw new IllegalArgumentException("Administrator ID must not be blank");
        }
        String normalized = openId.trim();
        if (normalized.length() > 256 || normalized.chars().anyMatch(Character::isWhitespace)
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Administrator ID contains invalid characters");
        }
        return normalized;
    }

    public enum AddResult {
        ADDED,
        ALREADY_PRESENT
    }

    public enum RemoveResult {
        REMOVED,
        CONFIGURED,
        NOT_FOUND
    }

    public record AdminView(String openId, boolean configured, boolean persisted) {
    }
}
