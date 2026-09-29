package xyz.zcraft.seira.ai.provider;

import xyz.zcraft.seira.config.LLMConfig;

public class ChatProviders {
    public static ChatProvider newHiAgentChatProvider(LLMConfig config) {
        return new HiAgentProvider(config);
    }
}
