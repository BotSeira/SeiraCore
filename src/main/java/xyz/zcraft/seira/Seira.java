package xyz.zcraft.seira;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.ConfigLoader;
import xyz.zcraft.seira.runtime.SeiraApplication;

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
            LOG.error("Invalid configuration! Please check your config.yml file: {}", e.getMessage());
            System.exit(1);
            return;
        }

        if (config.seira().debugMode()) {
            Configurator.setRootLevel(org.apache.logging.log4j.Level.DEBUG);
            LOG.warn("Debug mode is enabled");
        }

        try (SeiraApplication application = new SeiraApplication(config)) {
            Thread shutdownHook = new Thread(application::close, "seira-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                application.run();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The JVM is already shutting down and is running the hook.
                }
            }
        }
    }
}
