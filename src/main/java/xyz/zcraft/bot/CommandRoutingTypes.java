package xyz.zcraft.bot;

import xyz.zcraft.api.APIHelper;
import xyz.zcraft.data.PendingMessage;
import xyz.zcraft.data.ShortcutTarget;

@FunctionalInterface
interface ApiTaskExecutor {
    PendingMessage execute();
}

@FunctionalInterface
interface ImageResponseCreator {
    APIHelper.ImageResponse create();
}

@FunctionalInterface
interface ImageResponsePostProcessor {
    PendingMessage execute(APIHelper.ImageResponse response);
}

@FunctionalInterface
interface ReplayTaskCreator {
    APIHelper.ReplayTaskInfo create();
}

@FunctionalInterface
interface ApiTaskPostProcessor {
    PendingMessage execute();
}

@FunctionalInterface
interface ApiTaskFinalizer {
    void execute();
}

record ApiTask(
        String requestType,
        ApiTaskExecutor executor,
        ApiTaskPostProcessor postProcessor,
        ApiTaskFinalizer finalizer,
        boolean completeStatsAfterExecutor
) {
}

record TargetResolution(ShortcutTarget target, int consumedArgs) {
}

record UidResolution(Integer uid, String errorMessage) {
}

record UidListResolution(String[] uids, String errorMessage) {
}

record RouteDecision(PendingMessage initialMessage, ApiTask apiTask) {
    static RouteDecision sync(PendingMessage message) {
        return new RouteDecision(message, null);
    }

    static RouteDecision async(PendingMessage message, ApiTask apiTask) {
        return new RouteDecision(message, apiTask);
    }
}

