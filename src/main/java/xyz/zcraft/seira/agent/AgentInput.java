package xyz.zcraft.seira.agent;

import com.google.gson.*;
import xyz.zcraft.seira.bot.data.Attachment;

import java.util.List;
import java.util.Locale;

interface AgentInput {
    int MAX_HISTORY_TEXT_LENGTH = 8192;
    String OMITTED_HISTORY_IMAGE = "[历史图片已省略]";

    static JsonArray requestItems(List<AgentInput> input) {
        var items = new JsonArray();
        var images = new JsonArray();
        for (AgentInput item : input) {
            if (!(item instanceof ToolResult) && !images.isEmpty()) {
                items.add(Codec.imageMessage(images));
                images = new JsonArray();
            }
            items.add(toJson(item));
            if (item instanceof ToolResult result && !result.images().isEmpty()) {
                images.add(Codec.inputText("工具调用 " + result.callId() + " 返回的图片："));
                for (String url : result.images()) {
                    images.add(Codec.inputImage(url));
                }
            }
        }
        if (!images.isEmpty()) items.add(Codec.imageMessage(images));
        return items;
    }

    static JsonObject toJson(AgentInput input) {
        return switch (input) {
            case Text(var role, var content) -> Codec.message(role, content);
            case UserHistory(var message) -> Codec.userHistory(message);
            case UserMessage message -> Codec.userMessage(message);
            case ToolResult(var callId, var output, _) -> {
                var item = new JsonObject();
                item.addProperty("type", "function_call_output");
                item.addProperty("call_id", callId);
                item.addProperty("output", output.toString());
                yield item;
            }
            case ResponsesClient.OutputItem item -> item.payload();
            default -> throw new AssertionError();
        };
    }

    interface History extends AgentInput {
    }

    record Text(String role, String content) implements History {
    }

    record UserHistory(UserMessage message) implements History {
    }

    record UserMessage(String id, String name, String time, String quote, String text,
                       List<User> users, List<Attachment> attachments) implements AgentInput {
        List<Attachment> images() {
            return attachments.stream()
                    .filter(attachment -> attachment.contentType() != null && attachment.contentType()
                            .split(";", 2)[0].trim().toLowerCase(Locale.ROOT).startsWith("image/"))
                    .toList();
        }
    }

    record User(String id, String nickname, Long osuUid, String osuUsername) {
    }

    record ToolResult(String callId, JsonElement output, List<String> images) implements AgentInput {
        ToolResult(String callId, JsonElement output) {
            this(callId, output, List.of());
        }

        ToolResult imageTimedOut() {
            return new ToolResult(callId, new JsonPrimitive("图片加载超时，无法读取。"));
        }
    }

    final class Codec {
        private static final Gson GSON = new Gson();
        private static final String MISSING_IMAGE = "[图片附件已不可用，请向用户说明，不能猜测图片内容]";

        private Codec() {
        }

        private static JsonObject message(String role, String content) {
            var item = new JsonObject();
            item.addProperty("role", role);
            item.addProperty("content", content);
            return item;
        }

        private static JsonObject userMessage(UserMessage message) {
            String metadata = metadataText(message);
            var item = message("user", metadata);
            if (message.attachments().isEmpty()) return item;

            var content = new JsonArray();
            content.add(inputText(metadata));
            for (Attachment attachment : message.images()) {
                if (attachment.url() == null) {
                    content.add(inputText(MISSING_IMAGE));
                } else {
                    content.add(inputImage(attachment.url()));
                }
            }
            item.add("content", content);
            return item;
        }

        private static JsonObject imageMessage(JsonArray content) {
            var item = new JsonObject();
            item.addProperty("role", "user");
            item.add("content", content);
            return item;
        }

        private static JsonObject inputImage(String url) {
            var image = new JsonObject();
            image.addProperty("type", "input_image");
            image.addProperty("image_url", url);
            image.addProperty("detail", "auto");
            return image;
        }

        private static JsonObject userHistory(UserMessage message) {
            var content = new StringBuilder(metadataText(message));
            if (!message.attachments().isEmpty()) {
                content.append('\n');
                for (Attachment attachment : message.images()) {
                    content.append(attachment.url() == null ? MISSING_IMAGE : OMITTED_HISTORY_IMAGE).append('\n');
                }
            }
            return message("user", content.substring(0, Math.min(content.length(), MAX_HISTORY_TEXT_LENGTH)));
        }

        private static String metadataText(UserMessage message) {
            var metadata = new JsonObject();
            metadata.addProperty("id", message.id());
            metadata.addProperty("name", message.name());
            metadata.addProperty("time", message.time());
            metadata.addProperty("quote", message.quote());
            metadata.addProperty("text", message.text());
            var users = new JsonArray();
            for (User user : message.users()) {
                var item = new JsonObject();
                item.addProperty("id", user.id());
                item.addProperty("nickname", user.nickname());
                item.addProperty("osu_uid", user.osuUid());
                item.addProperty("osu_username", user.osuUsername());
                users.add(item);
            }
            metadata.add("users", users);
            metadata.add("attachments", GSON.toJsonTree(message.attachments()));
            return metadata.toString();
        }

        private static JsonObject inputText(String text) {
            var part = new JsonObject();
            part.addProperty("type", "input_text");
            part.addProperty("text", text);
            return part;
        }
    }
}
