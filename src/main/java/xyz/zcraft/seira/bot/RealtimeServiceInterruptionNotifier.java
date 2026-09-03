package xyz.zcraft.seira.bot;

import java.util.*;

public final class RealtimeServiceInterruptionNotifier {
    private static final String SCORE_WATCH = "成绩监视";
    private static final String RANK_GUESS = "猜 Rank";
    private static final String MULTIPLAYER_WATCH = "MP 监视";

    private final MessageSender sender;

    public RealtimeServiceInterruptionNotifier(MessageSender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    private static void addService(
            Map<String, LinkedHashSet<String>> servicesByGroup,
            Set<String> groupIds,
            String service
    ) {
        Objects.requireNonNull(groupIds);
        groupIds.stream().sorted().forEach(groupId -> servicesByGroup
                .computeIfAbsent(groupId, ignored -> new LinkedHashSet<>())
                .add(service));
    }

    private static String message(Set<String> services) {
        return "服务器即将重启，本群正在运行的实时服务（"
                + String.join("、", new ArrayList<>(services))
                + "）将会中断。服务器恢复后，请重新启动相关服务。";
    }

    public NotificationResult notifyGroups(
            Set<String> scoreWatchGroups,
            Set<String> rankGuessGroups,
            Set<String> multiplayerWatchGroups
    ) {
        Map<String, LinkedHashSet<String>> servicesByGroup = new LinkedHashMap<>();
        addService(servicesByGroup, scoreWatchGroups, SCORE_WATCH);
        addService(servicesByGroup, rankGuessGroups, RANK_GUESS);
        addService(servicesByGroup, multiplayerWatchGroups, MULTIPLAYER_WATCH);

        int sent = 0;
        for (Map.Entry<String, LinkedHashSet<String>> entry : servicesByGroup.entrySet()) {
            if (sender.sendGroupText(entry.getKey(), message(entry.getValue())) != null) {
                sent++;
            }
        }
        return new NotificationResult(servicesByGroup.size(), sent);
    }

    public record NotificationResult(int targetGroups, int sentGroups) {
        public int failedGroups() {
            return targetGroups - sentGroups;
        }
    }
}
