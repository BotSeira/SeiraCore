package xyz.zcraft.seira.config;

public record AppConfig(
        SeiraConfig seira,
        OstellaConfig ostella,
        BindingConfig binding,
        QqConfig qq,
        CosConfig cos,
        DiscordConfig discord,
        BridgeConfig bridge,
        LLMConfig llm
) {
    public AppConfig {
        discord = discord == null ? DiscordConfig.disabled() : discord;
        bridge = bridge == null ? BridgeConfig.defaults() : bridge;
        llm = llm == null ? new LLMConfig(null, null) : llm;
    }
}

