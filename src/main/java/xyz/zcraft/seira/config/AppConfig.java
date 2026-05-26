package xyz.zcraft.seira.config;

public record AppConfig(
        SeiraConfig seira,
        OstellaConfig ostella,
        BindingConfig binding,
        QqConfig qq,
        CosConfig cos
) {
}

