package xyz.zcraft.seira.ai.provider;

import xyz.zcraft.seira.ai.StreamHandler;
import xyz.zcraft.seira.ai.data.AgentFile;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

public interface ChatProvider {
    int CONTEXT_SIZE = 30;

    void recordHistory(String groupId, String sender, String message);

    String input(
            String groupId, String openId, String rawContent,
            Function<String, String> contextFunc, StreamHandler handler,
            List<AgentFile> attachments, String refContent
    );

    StopStatus requireStop(String groupId, String openId);

    boolean isRunning(String groupId, String openId);

    boolean clearState(String groupId, String openId);

    int clearStateOfGroup(String groupId);

    int clearStateOfUser(String openId);

    Set<String> activeGroupIds();

    enum StopStatus {
        SUCCESS,
        NO_CONVERSATION,
        NOT_SUPPORTED,
        FAILED
    }
}
