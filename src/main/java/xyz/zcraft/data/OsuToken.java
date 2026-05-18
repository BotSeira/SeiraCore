package xyz.zcraft.data;

public record OsuToken(String accessToken, String refreshToken, long expiresIn, long refreshedAt) {
    public boolean isExpired() {
        return System.currentTimeMillis() > refreshedAt + expiresIn * 1000 - 60 * 1000;
    }
}
