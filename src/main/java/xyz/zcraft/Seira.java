package xyz.zcraft;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import xyz.zcraft.binding.BindingHelper;
import xyz.zcraft.binding.UserDataStore;
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

        if (!ConfigLoader.configExists()) {
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

        if (config.seira().debugMode()) {
            Configurator.setRootLevel(org.apache.logging.log4j.Level.DEBUG);
            LOG.warn("Debug mode is enabled");
        }

        UserDataStore.init(config.seira().sqlitePath());
        BindingHelper.init(config.binding());

        new QQBot(config);
    }
}
