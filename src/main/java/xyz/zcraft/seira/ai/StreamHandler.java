package xyz.zcraft.seira.ai;

public interface StreamHandler {
    void onText(String message);

    void onComplete(String fullText);

    void onError(String errorCode, String errorMsg);
}
