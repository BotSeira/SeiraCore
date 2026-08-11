package xyz.zcraft.seira.discord;

public record DownloadedMedia(byte[] data, String filename, String contentType, String sourceUrl) {
}
