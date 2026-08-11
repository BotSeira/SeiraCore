package xyz.zcraft.seira.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {
    public static final Path CONFIG_PATH = Path.of("config.yml");
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile(
            "\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}"
    );

    public static AppConfig loadConfig() {
        try {
            String yaml = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            return ConfigValidator.validate(MAPPER.readValue(
                    expandEnvironment(yaml, System.getenv()), AppConfig.class
            ));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read configuration", e);
        }
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

    static String expandEnvironment(String value, Map<String, String> environment) {
        Matcher matcher = ENVIRONMENT_VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = environment.get(matcher.group(1));
            if (replacement == null) {
                replacement = matcher.group(2);
            }
            if (replacement == null) {
                throw new IllegalArgumentException("Environment variable is not set: " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
