package xyz.zcraft.seira.command;

import xyz.zcraft.seira.api.APIHelper;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ReplayResultStore {
    private final ConcurrentMap<String, APIHelper.ReplayRenderResult> results = new ConcurrentHashMap<>();

    void put(String taskId, APIHelper.ReplayRenderResult result) {
        results.put(requireTaskId(taskId), Objects.requireNonNull(result));
    }

    public APIHelper.ReplayRenderResult get(String taskId) {
        return results.get(requireTaskId(taskId));
    }

    public void remove(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            results.remove(taskId);
        }
    }

    private static String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task ID must not be blank");
        }
        return taskId;
    }
}
