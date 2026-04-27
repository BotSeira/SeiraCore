package xyz.zcraft.data;

import java.util.concurrent.ConcurrentHashMap;

public class VideoRenderRecord {
    private final ConcurrentHashMap<String, String> renderRecord = new ConcurrentHashMap<>();

    public void updateRenderTask(String uid, String jobId) {
        renderRecord.put(uid, jobId);
    }

    public boolean hasRenderTask(String uid) {
        return renderRecord.containsKey(uid);
    }

    public String getRenderTask(String uid) {
        return renderRecord.get(uid);
    }

    public void removeRenderTask(String uid) {
        renderRecord.remove(uid);
    }
}
