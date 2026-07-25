package xyz.zcraft.seira.data;

public record UploadedImage(String url, int width, int height) {
    public String toMarkdown() {
        return "![image #" + width + "px #" + height + "px](" + url + ")";
    }
}
