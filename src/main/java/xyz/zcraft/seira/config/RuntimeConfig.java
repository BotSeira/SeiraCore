package xyz.zcraft.seira.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe runtime view of configuration.
 *
 * <p>Only settings that do not own external resources are replaced live.
 * Changes to database, network listener, credentials and scheduled services are
 * reported as requiring a restart.</p>
 */
public final class RuntimeConfig {
    private static final Logger LOG = LogManager.getLogger(RuntimeConfig.class);

    private final AtomicReference<AppConfig> current;
    private final Supplier<AppConfig> loader;
    private final CopyOnWriteArrayList<Consumer<ReloadResult>> listeners = new CopyOnWriteArrayList<>();
    private volatile List<String> pendingRestart = List.of();

    public RuntimeConfig(AppConfig initial) {
        this(initial, ConfigLoader::loadConfig);
    }

    RuntimeConfig(AppConfig initial, Supplier<AppConfig> loader) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    private static AppConfig mergeReloadable(AppConfig previous, AppConfig loaded) {
        SeiraConfig oldSeira = previous.seira();
        SeiraConfig newSeira = loaded.seira();
        SeiraConfig effectiveSeira = new SeiraConfig(
                oldSeira.sqlitePath(),
                newSeira.directUrl(),
                newSeira.queueMessageInGroup(),
                oldSeira.watchIntervalMinutes(),
                oldSeira.multiplayerWatchIntervalSeconds(),
                newSeira.debugMode(),
                newSeira.adminIds() == null ? List.of() : List.copyOf(newSeira.adminIds())
        );
        return new AppConfig(
                effectiveSeira,
                previous.ostella(),
                previous.binding(),
                previous.qq(),
                previous.cos(),
                previous.discord(),
                previous.bridge()
        );
    }

    private static List<String> appliedChanges(AppConfig previous, AppConfig loaded) {
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "seira.directUrl", previous.seira().directUrl(), loaded.seira().directUrl());
        addIfChanged(changed, "seira.queueMessageInGroup", previous.seira().queueMessageInGroup(),
                loaded.seira().queueMessageInGroup());
        addIfChanged(changed, "seira.debugMode", previous.seira().debugMode(), loaded.seira().debugMode());
        addIfChanged(changed, "seira.adminIds", previous.seira().adminIds(), loaded.seira().adminIds());
        return List.copyOf(changed);
    }

    private static List<String> restartRequiredChanges(AppConfig previous, AppConfig loaded) {
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "seira.sqlitePath", previous.seira().sqlitePath(), loaded.seira().sqlitePath());
        addIfChanged(changed, "seira.watchIntervalMinutes", previous.seira().watchIntervalMinutes(),
                loaded.seira().watchIntervalMinutes());
        addIfChanged(changed, "seira.multiplayerWatchIntervalSeconds",
                previous.seira().multiplayerWatchIntervalSeconds(),
                loaded.seira().multiplayerWatchIntervalSeconds());
        addIfChanged(changed, "ostella", previous.ostella(), loaded.ostella());
        addIfChanged(changed, "binding", previous.binding(), loaded.binding());
        addIfChanged(changed, "qq", previous.qq(), loaded.qq());
        addIfChanged(changed, "cos", previous.cos(), loaded.cos());
        addIfChanged(changed, "discord", previous.discord(), loaded.discord());
        addIfChanged(changed, "bridge", previous.bridge(), loaded.bridge());
        return List.copyOf(changed);
    }

    private static void addIfChanged(List<String> changed, String name, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(name);
        }
    }

    public AppConfig current() {
        return current.get();
    }

    public List<String> pendingRestart() {
        return pendingRestart;
    }

    public void addReloadListener(Consumer<ReloadResult> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Reads and validates the source configuration without applying it.
     */
    public AppConfig validateSource() {
        return loader.get();
    }

    public synchronized ReloadResult reload() {
        AppConfig previous = current.get();
        AppConfig loaded = loader.get();
        List<String> applied = appliedChanges(previous, loaded);
        List<String> restartRequired = restartRequiredChanges(previous, loaded);
        AppConfig effective = mergeReloadable(previous, loaded);

        current.set(effective);
        pendingRestart = List.copyOf(restartRequired);
        ReloadResult result = new ReloadResult(effective, applied, pendingRestart);
        for (Consumer<ReloadResult> listener : listeners) {
            try {
                listener.accept(result);
            } catch (RuntimeException e) {
                LOG.error("Runtime configuration listener failed", e);
            }
        }
        return result;
    }

    public record ReloadResult(
            AppConfig effectiveConfig,
            List<String> applied,
            List<String> restartRequired
    ) {
    }
}
