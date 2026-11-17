package xyz.zcraft.seira.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import xyz.zcraft.seira.bot.QQApi;
import xyz.zcraft.seira.bot.data.SetMemberMuteState;
import xyz.zcraft.seira.db.UserDataStore;

import java.time.Duration;
import java.util.*;

class JavaTools {
    private static final Gson GSON = new Gson();
    static void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Tool(
                        "get_osu_binding",
                        "查询本群会话参与者绑定的 osu! UID；未绑定时返回 null。",
                        ToolSchema.object()
                                .property("user", ToolSchema.string().description("群内发送者标识，例如 u1"))
                                .property("users", ToolSchema.array(ToolSchema.string()).description("群内发送者标识列表，例如 [u3, u4, u8]"))
//                        .required("participant")
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
}
