package xyz.zcraft.seira.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class VersionInfo {
    private static final String VERSION = loadVersion();

    public static String getVersion() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream input = VersionInfo.class
                .getResourceAsStream("/version.properties")) {

            if (input == null) {
                return "unknown";
            }

            Properties properties = new Properties();
            properties.load(input);

            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}