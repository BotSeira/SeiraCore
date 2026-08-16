package xyz.zcraft.seira.discord;

import java.util.Locale;
import java.util.Optional;

final class MediaFormat {
    private MediaFormat() {
    }

    static Optional<String> detectContentType(byte[] data) {
        if (startsWith(data, "GIF87a") || startsWith(data, "GIF89a")) return Optional.of("image/gif");
        if (data.length >= 8
                && unsigned(data[0]) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && unsigned(data[4]) == 0x0D && unsigned(data[5]) == 0x0A
                && unsigned(data[6]) == 0x1A && unsigned(data[7]) == 0x0A) {
            return Optional.of("image/png");
        }
        if (data.length >= 3 && unsigned(data[0]) == 0xFF
                && unsigned(data[1]) == 0xD8 && unsigned(data[2]) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (data.length >= 12 && startsWith(data, "RIFF", 0) && startsWith(data, "WEBP", 8)) {
            return Optional.of("image/webp");
        }
        if (data.length >= 12 && startsWith(data, "ftyp", 4)) return Optional.of("video/mp4");
        if (startsWith(data, "OggS")) return Optional.of("audio/ogg");
        if (startsWith(data, "%PDF-")) return Optional.of("application/pdf");
        return Optional.empty();
    }

    static String normalizeContentType(byte[] data, String declaredContentType) {
        return detectContentType(data).orElseGet(() -> {
            if (declaredContentType == null || declaredContentType.isBlank()) {
                return "application/octet-stream";
            }
            return declaredContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        });
    }

    static String normalizeFilename(String filename, String contentType) {
        String extension = extensionFor(contentType);
        if (extension.isEmpty()) return filename;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(extension) || (".jpg".equals(extension) && lower.endsWith(".jpeg"))) {
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename) + extension;
    }

    static String extensionFor(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "audio/ogg" -> ".ogg";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private static boolean startsWith(byte[] data, String signature) {
        return startsWith(data, signature, 0);
    }

    private static boolean startsWith(byte[] data, String signature, int offset) {
        if (data.length < offset + signature.length()) return false;
        for (int index = 0; index < signature.length(); index++) {
            if (unsigned(data[offset + index]) != signature.charAt(index)) return false;
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
