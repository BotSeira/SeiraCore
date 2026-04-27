package xyz.zcraft.config;

public record CosConfig(
        String secretId,
        String secretKey,
        String region,
        String bucket,
        String baseUrl,
        String keyPrefix
) {
    public boolean isConfigured() {
        return notBlank(secretId)
                && notBlank(secretKey)
                && notBlank(region)
                && notBlank(bucket);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

