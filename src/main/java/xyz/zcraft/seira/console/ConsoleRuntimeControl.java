package xyz.zcraft.seira.console;

import xyz.zcraft.seira.watch.WatchView;

import java.time.Duration;
import java.util.List;

/**
 * Runtime operations exposed to the trusted local administration console.
 */
public interface ConsoleRuntimeControl {
    RuntimeStatus status();

    boolean reconnectGateway();

    boolean requestWatchPoll();

    List<GroupWatches> listWatches();

    List<WatchView> listWatches(String groupId);

    WatchView removeWatch(String groupId, long osuUserId);

    int clearWatches(String groupId);

    CacheControlResult controlCache(String operation, String type, long id);

    void requestStop();

    ConsoleCommandProcessor.ConsoleResult listPanels(String scope);

    ConsoleCommandProcessor.ConsoleResult getPanel(String panelId);

    ConsoleCommandProcessor.ConsoleResult createPanel(String scope, String jsonPath);

    ConsoleCommandProcessor.ConsoleResult deletePanel(String panelId);

    ConsoleCommandProcessor.ConsoleResult editPanel(String panelId, String jsonPath);

    record RuntimeStatus(
            boolean running,
            boolean gatewayConnected,
            boolean tokenValid,
            boolean watchServiceRunning,
            int watchedGroups,
            int watchTasks,
            Duration watchPollInterval
    ) {
    }

    record GroupWatches(String groupId, List<WatchView> watches) {
        public GroupWatches {
            watches = List.copyOf(watches);
        }
    }

    record CacheControlResult(String operation, String type, long id, List<CacheNodeResult> nodes) {
        public CacheControlResult {
            nodes = List.copyOf(nodes);
        }
    }

    record CacheNodeResult(
            String node,
            String status,
            String path,
            Long sizeBytes,
            String modifiedAt,
            String message
    ) {
    }
}
