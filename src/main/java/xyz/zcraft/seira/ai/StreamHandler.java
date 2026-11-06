package xyz.zcraft.seira.ai;

@Deprecated
public interface StreamHandler {
    void onText(String message);

    void onComplete(String fullText);

    void onError(String errorCode, String errorMsg);
}
