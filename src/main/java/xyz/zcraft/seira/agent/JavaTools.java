package xyz.zcraft.seira.agent;

import com.google.gson.*;
import xyz.zcraft.seira.bot.QQApi;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.SetMemberMuteState;
import xyz.zcraft.seira.bot.data.msg.CardMessage;
import xyz.zcraft.seira.bot.data.msg.PendingMessage;
import xyz.zcraft.seira.config.AgentConfig;
import xyz.zcraft.seira.db.UserDataStore;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class JavaTools {
    private static final Gson GSON = new Gson();

    static void register(ToolRegistry registry, AgentConfig config) {
        registerImageTools(registry, config);
        registerGroupTools(registry);
        registerFileTools(registry);
        registerMessageCardTool(registry);
        registerBalanceTool(registry, config);
        registerImageReadTool(registry);
    }

    private static void registerMessageCardTool(ToolRegistry registry) {
        registry.register(new ToolRegistry.Tool(
                "send_card_message",
                "发送卡片消息到当前对话中。客户端会显示结构化卡片，点击可以打开指定URL。",
                ToolSchema.object()
                        .property("title", ToolSchema.string().description("卡片标题"))
                        .property("description", ToolSchema.string().description("卡片小文字描述"))
                        .property("url", ToolSchema.string().description("点击打开的URL"))
                        .property("picUrl", ToolSchema.string().description("卡片配图的图片URL"))
                        .required("title", "description", "url", "picUrl")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (args, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }

                    final String title = args.get("title").getAsString();
                    final String description = args.get("description").getAsString();
                    final String url = args.get("url").getAsString();
                    final String picUrl = args.get("picUrl").getAsString();

                    context.notice().accept(CardMessage.ofCard(title, description, url, picUrl));

                    return GSON.toJsonTree(Map.of("sent", true));
                })
        );
    }

    private static void registerImageReadTool(ToolRegistry registry) {
        registry.register(new ToolRegistry.Tool(
                "read_image",
                "将指定 URL 的图片加入到对话上下文中供读取。",
                ToolSchema.object()
                        .property("url", ToolSchema.string().description("可访问的图片 HTTP(S) URL"))
                        .required("url")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (args, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }

                    var uri = URI.create(args.get("url").getAsString());
                    if (uri.getHost() == null || uri.getUserInfo() != null
                            || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                        throw new IllegalArgumentException("An HTTP(S) image URL is required");
                    }
                    context.imageOutput().accept(uri.toString());
                    return JsonNull.INSTANCE;
                })
        );
    }

    private static void registerFileTools(ToolRegistry registry) {
        registry.register(new ToolRegistry.Tool(
                "upload_attachment",
                "异步从文件URL上传附件到当前对话中。",
                ToolSchema.object()
                        .property("url", ToolSchema.string().description("指向附件文件的URL。"))
                        .property("filename", ToolSchema.string().description("附件文件的名称。若为空则会尝试从URL推断。"))
                        .required("url")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (args, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }

                    final String url = args.get("url").getAsString();
                    final String filename = args.get("filename").getAsString();

                    registry.getExecutor().submit(() -> {
                        context.notice().accept(PendingMessage.ofMarkdownRaw("> 正在上传附件..."));
                        final FileInfo uploadedFile = registry.getFileUploader().upload(context.conversationId(), url, filename);
                        context.notice().accept(PendingMessage.ofUploadedFile(uploadedFile, url));
                    });

                    return GSON.toJsonTree(Map.of("enqueued", true));
                })
        );

        registry.register(new ToolRegistry.Tool(
                "upload_attachment_multi",
                "异步从多个文件URL上传附件到当前对话中。",
                ToolSchema.object()
                        .property("files", ToolSchema.array(ToolSchema.object().description("要上传的文件列表。")
                                .property("url", ToolSchema.string().description("指向附件文件的URL。"))
                                .property("filename", ToolSchema.string().description("附件文件的名称。若为空则会尝试从URL推断。"))
                                .required("url")
                                .additionalProperties(false))
                        )
                        .required("files")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (args, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }
                    final JsonArray files = args.get("files").getAsJsonArray();

                    context.notice().accept(PendingMessage.ofMarkdownRaw("> 正在上传 %d 个附件...".formatted(files.size())));

                    registry.getExecutor().submit(() -> {
                        for (JsonElement file : files) {
                            final JsonObject fileObj = file.getAsJsonObject();
                            final String url = fileObj.get("url").getAsString();
                            final String filename = fileObj.get("filename").getAsString();
                            final FileInfo uploadedFile = registry.getFileUploader().upload(context.conversationId(), url, filename);
                            context.notice().accept(PendingMessage.ofUploadedFile(uploadedFile, url));
                        }
                    });

                    return GSON.toJsonTree(Map.of("enqueued", true));
                })
        );
    }

    private static void registerGroupTools(ToolRegistry registry) {
        registry.register(new ToolRegistry.Tool(
                        "get_osu_binding",
                        "查询本群会话参与者绑定的 osu! UID；未绑定时返回 null。",
                        ToolSchema.object()
                                .property("user", ToolSchema.string().description("群内发送者标识，例如 u1"))
                                .property("users", ToolSchema.array(ToolSchema.string()).description("群内发送者标识列表，例如 [u3, u4, u8]"))
                                .additionalProperties(false)
                                .toJson(),
                        Set.of(),
                        Duration.ofSeconds(5),
                        (arguments, context) -> {
                            Set<String> aliases = new HashSet<>();
                            if (arguments.has("users") && arguments.get("users").isJsonArray()) {
                                JsonArray users = arguments.get("users").getAsJsonArray();
                                users.forEach(e -> aliases.add(e.getAsString()));
                            }
                            if (arguments.has("user") && arguments.get("user").isJsonPrimitive()) {
                                String alias = arguments.get("user").getAsString();
                                aliases.add(alias);
                            }

                            JsonObject result = new JsonObject();

                            aliases.forEach(alias -> {
                                String openId = context.aliases().entrySet().stream()
                                        .filter(e -> e.getValue().equals(alias))
                                        .map(Map.Entry::getKey)
                                        .findFirst()
                                        .orElseThrow(IllegalArgumentException::new);

                                if (!UserDataStore.isGroupMember(context.conversationId(), openId)) {
                                    throw new IllegalArgumentException("Not a group member");
                                }

                                final Long osuUid = UserDataStore.findBoundUid(openId);
                                final Optional<String> osuName = Optional.ofNullable(osuUid).flatMap(UserDataStore::findUsername);

                                JsonObject user = new JsonObject();
                                user.addProperty("osu_uid", osuUid);
                                osuName.ifPresent(name -> user.addProperty("osu_username", name));

                                result.add(alias, user);
                            });

                            return result;
                        })
        );

        registry.register(new ToolRegistry.Tool(
                "get_group_osu_bindings",
                "获取当前群组中 Seira 已记录的所有绑定玩家，返回去重且按 UID 升序排列的 osu_uids 和 count。"
                        + "无绑定时返回空列表；不代表 QQ 实时完整群成员名单。可将 UID 列表用于批量查询玩家资料或成绩。",
                ToolSchema.object()
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (_, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }

                    JsonArray uids = new JsonArray();
                    UserDataStore.findBoundUidsByGroup(groupId).forEach(uids::add);

                    JsonObject result = new JsonObject();
                    result.add("osu_uids", uids);
                    result.addProperty("count", uids.size());
                    return result;
                })
        );

        registry.register(new ToolRegistry.Tool(
                "get_group_info",
                "获取当前群组的基本信息，包括群名称，描述，成员人数等。",
                ToolSchema.object()
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (_, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }

                    return GSON.toJsonTree(QQApi.getGroupInfo(registry.getTokenManager().getToken(), groupId));
                })
        );

        registry.register(new ToolRegistry.Tool(
                "restrict_group_member_chat_setting",
                "设置群成员禁言。需要管理员权限。",
                ToolSchema.object()
                        .property("op", ToolSchema.string().description("操作类型：`add` 增加禁言，`update` 更新禁言到期时间，`del` 解除禁言"))
                        .property("user", ToolSchema.string().description("群内发送者标识，例如 u1"))
                        .property("mute_expire_at", ToolSchema.string().description("禁言到期时间（RFC3339 格式，例如 `2026-11-17T01:40:00Z` ）；op=del 时可传空串表示立即解除禁言"))
                        .required("op", "user")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (args, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }

                    final String op = args.get("op").getAsString();
                    final String user = args.get("user").getAsString();
                    String openId = context.aliases().entrySet().stream()
                            .filter(e -> e.getValue().equals(user))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElseThrow(IllegalArgumentException::new);
                    final String muteExpireAt = args.get("mute_expire_at").getAsString();

                    QQApi.setGroupMute(registry.getTokenManager().getToken(), groupId, List.of(new SetMemberMuteState(op, openId, muteExpireAt)));

                    return GSON.toJsonTree(Map.of("success", true));
                })
        );

        registry.register(new ToolRegistry.Tool(
                "remove_group_member",
                "移除群成员。需要群主为机器人开启该操作权限。",
                ToolSchema.object()
                        .property("users", ToolSchema.array(ToolSchema.string()).description("群内发送者标识列表，例如 [u3, u4, u8]"))
                        .required("users")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (args, context) -> {
                    String groupId = context.conversationId();
                    if (groupId == null || groupId.isBlank()) {
                        throw new IllegalArgumentException("Group context is required");
                    }

                    List<String> openIds = new LinkedList<>();
                    final JsonArray users = args.get("users").getAsJsonArray();
                    for (JsonElement user : users) {
                        String openId = context.aliases().entrySet().stream()
                                .filter(e -> e.getValue().equals(user.getAsString()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElseThrow(IllegalArgumentException::new);
                        openIds.add(openId);
                    }

                    var result = QQApi.removeGroupMember(registry.getTokenManager().getToken(), groupId, openIds);

                    return GSON.toJsonTree(Map.of("remove_members_result", result));
                })
        );
    }

    private static void registerImageTools(ToolRegistry registry, AgentConfig config) {
        registry.register(new ToolRegistry.Tool(
                "generate_image",
                "根据提示词生成一张图片并发送到当前会话。",
                ToolSchema.object()
                        .property("prompt", ToolSchema.string().description("要生成的图片的详细描述"))
                        .required("prompt")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(config.timeoutSeconds()).plusSeconds(5),
                (args, context) -> {
                    if (config.models().image() == null) {
                        return GSON.toJsonTree(Map.of("error", "Image model is not configured"));
                    }

                    String prompt = args.get("prompt").getAsString();
                    if (prompt.isBlank()) throw new IllegalArgumentException("Prompt is required");
                    String model = config.models().image();
                    if (model.isBlank()) {
                        throw new IllegalStateException("Image model is not configured");
                    }
                    JsonObject body = new JsonObject();
                    body.addProperty("model", model);
                    body.addProperty("prompt", prompt);
                    body.addProperty("n", 1);
                    Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
                    ProxySelector proxy = config.proxy().enabled()
                            ? ProxySelector.of(new InetSocketAddress(config.proxy().host(), config.proxy().port()))
                            : ProxySelector.getDefault();
                    URI endpoint = URI.create(config.endpoint().replaceAll("/+$", "") + "/images/generations");
                    HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                    try (HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).proxy(proxy).build()) {
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() / 100 != 2) {
                            throw new IllegalStateException("Image generation HTTP status " + response.statusCode());
                        }
                        JsonArray data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data");
                        if (data == null || data.isEmpty()) {
                            throw new IllegalStateException("Image generation returned no image");
                        }
                        JsonObject image = data.get(0).getAsJsonObject();
                        PendingMessage message;
                        if (image.has("b64_json") && !image.get("b64_json").isJsonNull()
                                && !image.get("b64_json").getAsString().isBlank()) {
                            message = PendingMessage.ofImageBase64(image.get("b64_json").getAsString());
                        } else if (image.has("url") && !image.get("url").isJsonNull()
                                && !image.get("url").getAsString().isBlank()) {
                            message = PendingMessage.ofFileUrl(image.get("url").getAsString());
                            message.setFileType(PendingMessage.FILE_TYPE_IMAGE);
                        } else {
                            throw new IllegalStateException("Image generation returned no image data or URL");
                        }
                        context.notice().accept(message);
                        return GSON.toJsonTree(Map.of("sent", true));
                    }
                })
        );

        registry.register(new ToolRegistry.Tool(
                "edit_image",
                "根据提示词修改指定 URL 的图片并发送到当前会话。",
                ToolSchema.object()
                        .property("prompt", ToolSchema.string().description("图片修改要求"))
                        .property("image_url", ToolSchema.string().description("待修改图片的 HTTP(S) URL"))
                        .required("prompt", "image_url")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(config.timeoutSeconds()).plusSeconds(5),
                (args, context) -> {
                    if (config.models().edit() == null) {
                        return GSON.toJsonTree(Map.of("error", "Image edit model is not configured"));
                    }

                    String prompt = args.get("prompt").getAsString();
                    if (prompt.isBlank()) throw new IllegalArgumentException("Prompt is required");
                    String model = config.models().edit();
                    if (model.isBlank()) {
                        throw new IllegalStateException("Image edit model is not configured");
                    }
                    JsonObject body = new JsonObject();
                    body.addProperty("model", model);
                    body.addProperty("prompt", prompt);
                    body.addProperty("n", 1);
                    String imageUrl = args.get("image_url").getAsString();
                    URI imageUri = URI.create(imageUrl);
                    if (!"http".equalsIgnoreCase(imageUri.getScheme()) && !"https".equalsIgnoreCase(imageUri.getScheme())) {
                        throw new IllegalArgumentException("Image URL must use HTTP(S)");
                    }
                    JsonObject inputImage = new JsonObject();
                    inputImage.addProperty("image_url", imageUrl);
                    JsonArray images = new JsonArray();
                    images.add(inputImage);
                    body.add("images", images);
                    Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
                    ProxySelector proxy = config.proxy().enabled()
                            ? ProxySelector.of(new InetSocketAddress(config.proxy().host(), config.proxy().port()))
                            : ProxySelector.getDefault();
                    URI endpoint = URI.create(config.endpoint().replaceAll("/+$", "") + "/images/edits");
                    HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                    try (HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).proxy(proxy).build()) {
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() / 100 != 2) {
                            throw new IllegalStateException("Image edit HTTP status " + response.statusCode());
                        }
                        JsonArray data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data");
                        if (data == null || data.isEmpty()) {
                            throw new IllegalStateException("Image edit returned no image");
                        }
                        JsonObject image = data.get(0).getAsJsonObject();
                        PendingMessage message;
                        if (image.has("b64_json") && !image.get("b64_json").isJsonNull()
                                && !image.get("b64_json").getAsString().isBlank()) {
                            message = PendingMessage.ofImageBase64(image.get("b64_json").getAsString());
                        } else if (image.has("url") && !image.get("url").isJsonNull()
                                && !image.get("url").getAsString().isBlank()) {
                            message = PendingMessage.ofFileUrl(image.get("url").getAsString());
                            message.setFileType(PendingMessage.FILE_TYPE_IMAGE);
                        } else {
                            throw new IllegalStateException("Image edit returned no image data or URL");
                        }
                        context.notice().accept(message);
                        return GSON.toJsonTree(Map.of("sent", true));
                    }
                })
        );
    }

    private static void registerBalanceTool(ToolRegistry registry, AgentConfig config) {
        registry.register(new ToolRegistry.Tool(
                "check_balance",
                "检查当前 API 账户的余额。",
                ToolSchema.object().additionalProperties(false).toJson(),
                Set.of(),
                Duration.ofSeconds(config.timeoutSeconds()).plusSeconds(5),
                (_, _) -> {
                    if (!config.usages().enabled()) {
                        return GSON.toJsonTree(Map.of("error", "Balance checking is not enabled"));
                    }

                    Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
                    ProxySelector proxy = config.proxy().enabled()
                            ? ProxySelector.of(new InetSocketAddress(config.proxy().host(), config.proxy().port()))
                            : ProxySelector.getDefault();
                    final Matcher matcher = Pattern.compile("^(https?://.*?)(/.+)?$").matcher(config.endpoint());
                    if (!matcher.matches()) {
                        return GSON.toJsonTree(Map.of("error", "Balance checking config url is invalid"));
                    }
                    final String baseUrl = matcher.group(1);
                    URI endpoint = URI.create(baseUrl + config.usages().endpoint());
                    HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("Content-Type", "application/json")
                            .GET().build();
                    try (HttpClient client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(timeout)
                            .proxy(proxy)
                            .build()) {
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() / 100 != 2) {
                            throw new IllegalStateException("Balance checking HTTP status " + response.statusCode());
                        }

                        JsonElement elem = JsonParser.parseString(response.body());

                        for (String s : config.usages().path().split("\\.")) {
                            elem = elem.getAsJsonObject().get(s);
                        }

                        final double value = elem.getAsDouble();
                        final double unitValue = value / config.usages().rate();

                        return GSON.toJsonTree(Map.of(
                                "units", unitValue,
                                "unitName", config.usages().unit(),
                                "tokensPerUnit", config.usages().rate(),
                                "tokens", value
                        ));
                    }
                })
        );
    }
}
