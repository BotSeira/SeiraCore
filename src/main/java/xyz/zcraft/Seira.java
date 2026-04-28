package xyz.zcraft;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.binding.UserBindingStore;
import xyz.zcraft.bot.QQBot;
import xyz.zcraft.config.AppConfig;
import xyz.zcraft.config.ConfigLoader;

import java.io.IOException;

public class Seira {
    private static final Logger LOG = LogManager.getLogger(Seira.class);
    @Getter
    private static AppConfig config;

    static void main() {
        LOG.info("Loading config");

        if(!ConfigLoader.configExists()) {
            LOG.warn("Config file does not exist, copying default config. Please check your config file.");
            try {
                ConfigLoader.copyDefaultConfig();
            } catch (IOException e) {
                LOG.error("Failed to copy default config", e);
            }

            System.exit(0);
        }

        try {
            config = ConfigLoader.loadConfig();
        } catch (Exception e) {
            LOG.error("Invalid configuration! Please check your config.yml file.");
            System.exit(1);
            return;
        }

        UserBindingStore.init(config.seira().sqlitePath());

        new QQBot(config);
    }
}
