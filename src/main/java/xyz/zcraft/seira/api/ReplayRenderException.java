package xyz.zcraft.seira.api;

public final class ReplayRenderException extends RuntimeException {
    public ReplayRenderException(String status, String error) {
        super(formatMessage(status, error));
    }

    static String formatMessage(String status, String error) {
        if (error != null && !error.isBlank()) {
            return "回放渲染失败：" + error.trim();
        }
        return "回放渲染失败，状态：" + (status == null || status.isBlank() ? "failed" : status);
    }
}
