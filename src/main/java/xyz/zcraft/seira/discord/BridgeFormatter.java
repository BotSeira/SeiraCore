package xyz.zcraft.seira.discord;

import xyz.zcraft.seira.bot.QqFaceNames;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BridgeFormatter {
    private static final int DISCORD_TEXT_LIMIT = 2_000;
    private static final int QQ_TEXT_LIMIT = 1_800;
    private static final Pattern FORMAT_PLACEHOLDER = Pattern.compile("\\{(name|id|message)}");
    private static final Pattern QQ_USER_MENTION = Pattern.compile(
            "<qqbot-at-user\\s+id=\"([^\"]+)\"\\s*/>", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QQ_EVERYONE_MENTION = Pattern.compile(
            "<qqbot-at-everyone\\s*/>", Pattern.CASE_INSENSITIVE
    );
    // <faceType=1,faceId="86",ext="eyJ0ZXh0Ijoi5oCE54GrIn0=">
    private static final Pattern QQ_FACE = Pattern.compile(
            "<faceType=[1|3],faceId=\"(\\d+)\",ext=\"([^\"]+)\">", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QQ_MEME = Pattern.compile(
            "<faceType=6,faceId=\"0\",ext=\"([^\"]+)\">", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QQ_MEME_ALT = Pattern.compile(
            "<faceType=4,faceId=\"\",ext=\"([^\"]+)\">", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SIMPLE_MENTION = Pattern.compile("<@([^>]+)>");
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[[^]\\r\\n]*]\\(\\s*<?(https?://[^\\s)>]+)>?\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAW_URL = Pattern.compile(
            "https?://[^\\s<>\\])]+", Pattern.CASE_INSENSITIVE
    );

    private BridgeFormatter() {
    }

    static String render(String template, String name, String id, String message) {
        Matcher matcher = FORMAT_PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "name" -> name == null ? "" : name;
                case "id" -> id == null ? "" : id;
                case "message" -> message == null ? "" : message;
                default -> throw new IllegalStateException("Unexpected placeholder");
            };
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString().stripTrailing();
    }

    static String normalizeQqText(String value) {
        if (value == null || value.isBlank()) return "";
        String result = QQ_USER_MENTION.matcher(value).replaceAll("@$1");
        result = QQ_EVERYONE_MENTION.matcher(result).replaceAll("@everyone");
        result = QQ_FACE.matcher(result).replaceAll(matchResult -> QqFaceNames.describe(matchResult.group(1)));
        result = SIMPLE_MENTION.matcher(result).replaceAll("@$1");
        result = QQ_MEME.matcher(result).replaceAll("[动画表情]");
        result = QQ_MEME_ALT.matcher(result).replaceAll("[动画表情:未知]");
        return result.strip();
    }

    static String escapeDiscordMentions(String value) {
        return value == null ? "" : value.replace("@", "@\u200B");
    }

    static String removeSourceUrl(String text, String sourceUrl) {
        if (text == null || sourceUrl == null || sourceUrl.isBlank()) return text == null ? "" : text;
        String markdownWithSource = "\\[[^]\\r\\n]*]\\(\\s*<?"
                + Pattern.quote(sourceUrl) + ">?\\s*\\)";
        return text.replaceAll(markdownWithSource, "")
                .replace("<" + sourceUrl + ">", "")
                .replace(sourceUrl, "")
                .replace("<>", "")
                .replaceAll("\\[\\s*]\\(\\s*\\)", "")
                .strip();
    }

    static List<String> findImageUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Matcher markdown = MARKDOWN_LINK.matcher(text);
        while (markdown.find()) addImageUrl(urls, markdown.group(1));
        Matcher raw = RAW_URL.matcher(text);
        while (raw.find()) addImageUrl(urls, raw.group());
        return List.copyOf(urls);
    }

    private static void addImageUrl(LinkedHashSet<String> urls, String value) {
        try {
            String path = URI.create(value).getPath();
            String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".gif") || lower.endsWith(".png") || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
                urls.add(value);
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed links and leave them as text.
        }
    }

    static List<String> splitDiscordText(String value) {
        return splitText(value, DISCORD_TEXT_LIMIT, 1_000);
    }

    static List<String> splitQqText(String value) {
        return splitText(value, QQ_TEXT_LIMIT, 900);
    }

    private static List<String> splitText(String value, int limit, int preferredBreakAfter) {
        if (value == null || value.isBlank()) return List.of();
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(start + limit, value.length());
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            int newline = value.lastIndexOf('\n', end - 1);
            if (end < value.length() && newline >= start + preferredBreakAfter) end = newline + 1;
            parts.add(value.substring(start, end));
            start = end;
        }
        return List.copyOf(parts);
    }
}
