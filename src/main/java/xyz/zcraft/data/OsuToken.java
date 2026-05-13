package xyz.zcraft.data;

public record OsuToken(String accessToken, String refreshToken, long expiresIn, long refreshedAt) {
}
