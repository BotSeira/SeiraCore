package xyz.zcraft.seira.config;

public record CosConfig(
        String secretId,
        String secretKey,
        String region,
        String bucket,
        String baseUrl,
        String keyPrefix
) {
}

