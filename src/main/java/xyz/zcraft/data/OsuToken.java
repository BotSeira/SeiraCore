package xyz.zcraft.data;

public record OsuToken(String accessToken, String refreshToken, long expireIn, long refreshedAt) {
}
