package xyz.zcraft.seira.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ConfigLoader {
    public static final Path CONFIG_PATH = Path.of("config.yml");

    public static AppConfig loadConfig() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(CONFIG_PATH, AppConfig.class);
    }

    public static boolean configExists() {
        return Files.exists(CONFIG_PATH);
    }

    public static void copyDefaultConfig() throws IOException {
        try (var in = ConfigLoader.class.getResourceAsStream("/seira-example-config.yml")) {
            if (in == null) {
                throw new IOException("Default config not found in resources");
            }
            Files.copy(in, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
