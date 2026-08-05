package xyz.zcraft.seira.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Validates configuration once at the application boundary. */
public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static AppConfig validate(AppConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            throw new IllegalArgumentException("Configuration is empty");
        }

        SeiraConfig seira = requireSection(config.seira(), "seira", errors);
        OstellaConfig ostella = requireSection(config.ostella(), "ostella", errors);
        BindingConfig binding = requireSection(config.binding(), "binding", errors);
        QqConfig qq = requireSection(config.qq(), "qq", errors);
        CosConfig cos = requireSection(config.cos(), "cos", errors);

        if (seira != null) {
            requireText(seira.sqlitePath(), "seira.sqlitePath", errors);
            requireHttpUri(seira.directUrl(), "seira.directUrl", errors);
        }
        if (ostella != null) {
            requireHttpUri(ostella.endpoint(), "ostella.endpoint", errors);
        }
        if (binding != null) {
            if (binding.listenPort() < 1 || binding.listenPort() > 65_535) {
                errors.add("binding.listenPort must be between 1 and 65535");
            }
            if (binding.listenPath() == null || !binding.listenPath().startsWith("/")) {
                errors.add("binding.listenPath must start with /");
            }
            if (binding.clientId() <= 0) {
                errors.add("binding.clientId must be positive");
            }
            requireText(binding.clientSecret(), "binding.clientSecret", errors);
        }
        if (qq != null) {
            requireText(qq.selfId(), "qq.selfId", errors);
            requireText(qq.appId(), "qq.appId", errors);
            requireText(qq.appSecret(), "qq.appSecret", errors);
        }
        if (cos != null) {
            requireText(cos.secretId(), "cos.secretId", errors);
            requireText(cos.secretKey(), "cos.secretKey", errors);
            requireText(cos.region(), "cos.region", errors);
            requireText(cos.bucket(), "cos.bucket", errors);
            if (cos.baseUrl() != null && !cos.baseUrl().isBlank()) {
                requireHttpUri(cos.baseUrl(), "cos.baseUrl", errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration: " + String.join("; ", errors));
        }
        return config;
    }

    private static <T> T requireSection(T value, String name, List<String> errors) {
        if (value == null) {
            errors.add("missing section " + name);
        }
        return value;
    }

    private static void requireText(String value, String name, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(name + " must not be blank");
        }
    }

    private static void requireHttpUri(String value, String name, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(name + " must not be blank");
            return;
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                errors.add(name + " must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException e) {
            errors.add(name + " must be a valid URL");
        }
    }
}
