package xyz.zcraft.platform;

import xyz.zcraft.config.AppConfig;
import xyz.zcraft.platform.napcat.NapcatPlatformAdapter;
import xyz.zcraft.platform.qq.QqPlatformAdapter;

public final class BotPlatformAdapters {
    private BotPlatformAdapters() {
    }

    public static BotPlatformAdapter create(AppConfig config) {
        return switch (config.seira().platform().toLowerCase()) {
            case "napcat" -> new NapcatPlatformAdapter(config);
            case "qq" -> new QqPlatformAdapter(config);
            default -> throw new IllegalStateException("Unsupported platform: " + config.seira().platform());
        };
    }
}

