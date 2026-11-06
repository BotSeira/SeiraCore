package xyz.zcraft.seira.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public class ResourceUtil {
    private static final Logger LOG = LogManager.getLogger(ResourceUtil.class);

    public static Optional<String> loadString(String resource) {
        try (var stream = ResourceUtil.class.getResourceAsStream(resource)) {
            return Optional.of(new String(Objects.requireNonNull(stream, resource + " resource not found").readAllBytes(),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.error("Failed to load binding HTML template {}", resource, e);
            return Optional.empty();
        }
    }
}
