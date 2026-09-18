package xyz.zcraft.seira.api.data;

import java.util.concurrent.ConcurrentHashMap;

public class VideoRenderRecord {
    private final ConcurrentHashMap<String, String> renderRecord = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> taskOwner = new ConcurrentHashMap<>();

    public void updateRenderTask(String uid, String jobId) {
        renderRecord.put(uid, jobId);
        taskOwner.put(jobId, uid);
    }

    public boolean hasRenderTask(String uid) {
        return renderRecord.containsKey(uid);
    }

    public String getRenderTask(String uid) {
        return renderRecord.get(uid);
    }

    public String getTaskOwner(String jobId) {
        return taskOwner.get(jobId);
    }

    @SuppressWarnings("unused")
    public void removeRenderTask(String uid) {
        renderRecord.remove(uid);
    }

    public void removeRenderTask(String uid, String jobId) {
        renderRecord.remove(uid, jobId);
        taskOwner.remove(jobId, uid);
    }
}
