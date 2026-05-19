package xyz.zcraft.seira.data;

public record Base64Bytes(byte[] bytes) {
    public String toBase64() {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
