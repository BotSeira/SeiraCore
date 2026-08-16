package xyz.zcraft.seira.api.data;

public record QqUploadRequest(String accessToken, String targetType, String targetId) {
    public QqUploadRequest {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("QQ access token must not be blank");
        }
        if (!"groups".equals(targetType) && !"users".equals(targetType)) {
            throw new IllegalArgumentException("QQ target type must be groups or users");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("QQ target id must not be blank");
        }
    }
}
