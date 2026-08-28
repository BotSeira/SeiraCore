package xyz.zcraft.seira.api;

public final class ReplayRenderException extends RuntimeException {
    public ReplayRenderException(String status, String error) {
        super(formatMessage(status, error));
    }

    static String formatMessage(String status, String error) {
        if (error != null && !error.isBlank()) {
            String possibleReason = tryParseError(error);
            return "回放渲染失败" + (possibleReason != null ? "，这可能是由于" + possibleReason : "") + "。日志输出：\n```\n" + error.trim() + "\n```\n";
        }
        return "回放渲染失败，状态：" + (status == null || status.isBlank() ? "failed" : status);
    }

    static String tryParseError(String error) {
        if (error.contains("Beatmap not found, closing...")) {
            return "镜像站谱面版本不正确";
        } else if (error.contains("Cannot run program \"xvfb-run\": Failed to exec spawn helper")) {
            return "服务器正在进行更新";
        } else {
            return null;
        }
    }
}
