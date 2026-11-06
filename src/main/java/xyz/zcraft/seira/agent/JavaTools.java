package xyz.zcraft.seira.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import xyz.zcraft.seira.db.UserDataStore;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

class JavaTools {
    static void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Tool(
                "get_osu_binding",
                "查询本群会话参与者绑定的 osu! UID；未绑定时返回 null。",
                ToolSchema.object()
                        .property("participant", ToolSchema.string().description("群内发送者标识，例如 u1"))
                        .required("participant")
                        .additionalProperties(false)
                        .toJson(),
                Set.of(),
                Duration.ofSeconds(5),
                (arguments, context) -> {
                    String alias = arguments.get("participant").getAsString();

                    String openId = context.aliases().entrySet().stream()
                            .filter(e -> e.getValue().equals(alias))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElseThrow(IllegalArgumentException::new);

                    if (!UserDataStore.isGroupMember(context.conversationId(), openId)) {
                        throw new IllegalArgumentException("Not a group member");
                    }

                    JsonObject result = new JsonObject();
                    result.addProperty("participant", alias);
                    result.addProperty("osu_uid", UserDataStore.findBoundUid(openId));
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
                (arguments, context) -> {
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
    }
}
