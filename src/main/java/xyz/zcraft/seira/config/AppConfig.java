package xyz.zcraft.seira.config;

public record AppConfig(
        SeiraConfig seira,
        OstellaConfig ostella,
        BindingConfig binding,
        QqConfig qq,
        CosConfig cos,
        DiscordConfig discord,
        BridgeConfig bridge
) {
    public AppConfig {
        discord = discord == null ? DiscordConfig.disabled() : discord;
        bridge = bridge == null ? BridgeConfig.defaults() : bridge;
    }

    public AppConfig(
            SeiraConfig seira,
            OstellaConfig ostella,
            BindingConfig binding,
            QqConfig qq,
            CosConfig cos
    ) {
        this(seira, ostella, binding, qq, cos, null, null);
    }
}

