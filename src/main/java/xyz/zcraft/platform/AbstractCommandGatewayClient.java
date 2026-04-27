package xyz.zcraft.platform;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.client.WebSocketClient;
import xyz.zcraft.Seira;
import xyz.zcraft.api.APIHelper;
import xyz.zcraft.api.ApiRequestException;
import xyz.zcraft.binding.UserBindingStore;
import xyz.zcraft.data.*;
import xyz.zcraft.util.ThreadHelper;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractCommandGatewayClient extends WebSocketClient implements PlatformGatewayClient {
    public static final String BO_USAGE = "用法：/bo <个数> [玩家ID/@用户]";
    public static final String NO_BIND_TIP = "你还没有绑定玩家ID，请先使用 /bind <玩家ID>";
    public static final String RS_USAGE = "用法：/rs <个数> [玩家ID/@用户]";
    public static final String M_USAGE = "用法：/m <铺面ID 或 快捷查询> [Mod]";
    public static final String S_USAGE = "用法：/s <成绩ID 或 快捷查询>";
    private static final Logger LOG = LogManager.getLogger(AbstractCommandGatewayClient.class);
    private static final String PREFIX = "/";
    private static final String R_USAGE = "用法：/r <成绩ID 或 快捷查询>";
    private static final String RSC_USAGE = "用法：/rsc <铺面ID或快捷查询> [+用户ID列表，逗号分隔]";
    private static final ApiRequestStats API_REQUEST_STATS = new ApiRequestStats();
    private static final Pattern USER_MACRO_PATTERN = Pattern.compile("(?i)^(rs|bo)(\\d+)$"); // bo25, rs1
    private static final Pattern SET_MACRO_PATTERN = Pattern.compile("^(\\d+)#(\\d+)$"); // 12345#1
    private static final Pattern BEATMAP_MACRO_PATTERN = Pattern.compile("^m(\\d+)$"); // m12345
    private static final Pattern CQ_AT_PATTERN = Pattern.compile("^\\[CQ:at,qq=(\\d+)(?:,.*)?]$");
    private static final Pattern PLAIN_AT_PATTERN = Pattern.compile("^@(\\d+)$");
    private final PlatformMessageSender messageSender;
    private final VideoRenderRecord videoRenderRecord = new VideoRenderRecord();

    protected AbstractCommandGatewayClient(URI serverUri, PlatformMessageSender messageSender) {
        super(serverUri);
        this.messageSender = messageSender;
    }

    protected void onPrivateMessageReceived(String userId, String messageId, String rawContent) {
        handleMessageReceived(userId, null, userId, messageId, rawContent, false);
    }

    protected void onGroupMessageReceived(String groupId, String senderUserId, String messageId, String rawContent) {
        handleMessageReceived(groupId, groupId, senderUserId, messageId, rawContent, true);
    }

    private void handleMessageReceived(String targetId, String groupId, String senderUserId, String messageId, String rawContent, boolean groupMessage) {
        LOG.info("Received {} message {} from {}: {}", groupMessage ? "group" : "private", messageId, senderUserId, rawContent);
        AtomicInteger messageSeqCounter = new AtomicInteger(1);
        try {
            String platform = Seira.getConfig().seira().platform();
            if (groupMessage && groupId != null && !groupId.isBlank() && senderUserId != null && !senderUserId.isBlank()) {
                UserBindingStore.upsertGroupMember(platform, groupId, senderUserId);
            }

            RouteDecision routeDecision = route(rawContent, senderUserId, groupId);
            if (routeDecision == null) {
                return;
            }

            sendOutboundMessage(targetId, messageId, groupMessage, routeDecision.initialMessage(), messageSeqCounter);

            ApiTask apiTask = routeDecision.apiTask();
            if (apiTask != null) {
                ThreadHelper.run(() -> processApiTask(targetId, messageId, groupMessage, apiTask, messageSeqCounter));
            }
        } catch (Exception e) {
            sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString("处理指令时发生错误，请稍后再试。"), messageSeqCounter);
            LOG.error("Failed to process inbound message {}", messageId, e);
        }
    }

    protected RouteDecision route(String rawContent, String senderUserId, String groupId) {
        if (rawContent == null || !rawContent.startsWith(PREFIX)) {
            return null;
        }

        String body = rawContent.substring(PREFIX.length()).trim();
        if (body.isEmpty()) {
            return RouteDecision.sync(PendingMessage.ofString("请输入指令。使用/help获取帮助。"));
        }

        String[] parts = body.split("\\s+");
        String command = parts[0].toLowerCase();
        String query = body.substring(command.length()).trim();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        String platform = Seira.getConfig().seira().platform();

        switch (command) {
            case "bind" -> {
                if (senderUserId == null || senderUserId.isBlank()) {
                    return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法绑定。请稍后重试。"));
                }
                if (args.length != 1) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/bind <玩家ID>"));
                }
                Integer uid = parsePositiveInt(args[0]);
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString("玩家ID必须是正整数。用法：/bind <玩家ID>"));
                }
                UserBindingStore.bind(platform, senderUserId, uid);
                return RouteDecision.sync(PendingMessage.ofString("绑定成功，已绑定到玩家ID: " + uid));
            }
            case "unbind" -> {
                if (senderUserId == null || senderUserId.isBlank()) {
                    return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法解绑。请稍后重试。"));
                }
                if (args.length != 0) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/unbind"));
                }
                boolean removed = UserBindingStore.unbind(platform, senderUserId);
                return RouteDecision.sync(PendingMessage.ofString(removed
                        ? "解绑成功。"
                        : "你当前还没有绑定玩家ID，无需解绑。"));
            }
            case "bo", "top" -> {
                if (args.length == 2) {
                    Integer n = parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(BO_USAGE));
                    }
                    UidResolution uidResolution = resolveUidArgument(args[1], platform);
                    if (uidResolution.errorMessage() != null) {
                        return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
                    }
                    if (uidResolution.uid() == null) {
                        return RouteDecision.sync(PendingMessage.ofString(BO_USAGE));
                    }
                    return queueApiRequest("bo", () -> PendingMessage.ofImageBase64(APIHelper.getBoN(n, uidResolution.uid())));
                } else if (args.length == 1) {
                    Integer n = parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(BO_USAGE));
                    }
                    Integer uid = resolveBoundUid(platform, senderUserId);
                    if (uid == null) {
                        return RouteDecision.sync(PendingMessage.ofString(NO_BIND_TIP));
                    }
                    return queueApiRequest("bo", () -> PendingMessage.ofImageBase64(APIHelper.getBoN(n, uid)));
                } else if (args.length == 0) {
                    ShortcutTarget target = parseTarget("bo1", platform, senderUserId);
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    return queueApiRequest("bo", () -> PendingMessage.ofImageBase64(APIHelper.getScore(target)));
                } else {
                    return RouteDecision.sync(PendingMessage.ofString(BO_USAGE));
                }
            }
            case "daily" -> {
                return queueApiRequest("daily", () -> PendingMessage.ofString(APIHelper.getDaily()));
            }
            case "mp" -> {
                return queueApiRequest("mp", () -> PendingMessage.ofString(APIHelper.getMultiplayerRooms()));
            }
            case "rs" -> {
                if (args.length == 2) {
                    Integer n = parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(RS_USAGE));
                    }
                    UidResolution uidResolution = resolveUidArgument(args[1], platform);
                    if (uidResolution.errorMessage() != null) {
                        return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
                    }
                    if (uidResolution.uid() == null) {
                        return RouteDecision.sync(PendingMessage.ofString(RS_USAGE));
                    }
                    return queueApiRequest("rs", () -> PendingMessage.ofImageBase64(APIHelper.getRecent(n, uidResolution.uid())));
                } else if (args.length == 1) {
                    Integer n = parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(RS_USAGE));
                    }
                    Integer uid = resolveBoundUid(platform, senderUserId);
                    if (uid == null) {
                        return RouteDecision.sync(PendingMessage.ofString(NO_BIND_TIP));
                    }
                    return queueApiRequest("rs", () -> PendingMessage.ofImageBase64(APIHelper.getRecent(n, uid)));
                } else if (args.length == 0) {
                    ShortcutTarget target = parseTarget("rs1", platform, senderUserId);
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    return queueApiRequest("rs", () -> PendingMessage.ofImageBase64(APIHelper.getScore(target)));
                } else {
                    return RouteDecision.sync(PendingMessage.ofString(RS_USAGE));
                }
            }
            case "m" -> {
                if (args.length >= 1) {
                    TargetResolution targetResolution = resolveTargetWithOptionalMention(args, platform, senderUserId);
                    ShortcutTarget target = targetResolution.target();
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    if (args.length > targetResolution.consumedArgs() + 1) {
                        return RouteDecision.sync(PendingMessage.ofString(M_USAGE));
                    }

                    String mod = args.length == targetResolution.consumedArgs() + 1
                            ? args[targetResolution.consumedArgs()]
                            : null;
                    return queueApiRequest("m", () -> PendingMessage.ofImageBase64(APIHelper.getBeatmap(target, mod)));
                } else {
                    return RouteDecision.sync(PendingMessage.ofString(M_USAGE));
                }
            }
            case "s" -> {
                if (args.length < 1 || args.length > 2) {
                    return RouteDecision.sync(PendingMessage.ofString(S_USAGE));
                }

                TargetResolution targetResolution = resolveTargetWithOptionalMention(args, platform, senderUserId);
                if (args.length != targetResolution.consumedArgs()) {
                    return RouteDecision.sync(PendingMessage.ofString(S_USAGE));
                }
                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                return queueApiRequest("s", () -> PendingMessage.ofImageBase64(APIHelper.getScore(target)));
            }
            case "r" -> {
                if (args.length < 1 || args.length > 2) {
                    return RouteDecision.sync(PendingMessage.ofString(R_USAGE));
                }

                TargetResolution targetResolution = resolveTargetWithOptionalMention(args, platform, senderUserId);
                if (args.length != targetResolution.consumedArgs()) {
                    return RouteDecision.sync(PendingMessage.ofString(R_USAGE));
                }
                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }
                return queueReplayTask("r", () -> {
                    final APIHelper.ReplayTaskInfo replayRenderTask = APIHelper.createReplayRenderTask(target);
                    videoRenderRecord.updateRenderTask(senderUserId, replayRenderTask.taskId());
                    return replayRenderTask;
                });
            }
            case "rsc" -> {
                if (args.length < 1 || args.length > 3) {
                    return RouteDecision.sync(PendingMessage.ofString(RSC_USAGE));
                }

                if (groupId == null || groupId.isBlank()) {
                    return RouteDecision.sync(PendingMessage.ofString("/rsc 仅支持群聊使用。"));
                }

                TargetResolution targetResolution = resolveTargetWithOptionalMention(args, platform, senderUserId);
                if (args.length - targetResolution.consumedArgs() > 1) {
                    return RouteDecision.sync(PendingMessage.ofString(RSC_USAGE));
                }
                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                String extraUidArg =
                        (args.length - targetResolution.consumedArgs() == 1) ? args[targetResolution.consumedArgs()] : null;
                UidListResolution uidListResolution = resolveRscUidList(platform, groupId, extraUidArg);
                if (uidListResolution.errorMessage() != null) {
                    return RouteDecision.sync(PendingMessage.ofString(uidListResolution.errorMessage()));
                }
                String[] uidArray = uidListResolution.uids();

                if (target.isMacro()) {
                    return queueReplayTask("rsc", () -> {
                        final APIHelper.ReplayTaskInfo replayShowcaseTask = APIHelper.createReplayShowcaseTask(target, uidArray);
                        videoRenderRecord.updateRenderTask(senderUserId, replayShowcaseTask.taskId());
                        return replayShowcaseTask;
                    });
                }

                if (target.explicitId() == null) {
                    return RouteDecision.sync(PendingMessage.ofString(RSC_USAGE));
                }
                return queueReplayTask("rsc", () -> {
                    final APIHelper.ReplayTaskInfo showcaseRenderTaskByBeatmap = APIHelper.createShowcaseRenderTaskByBeatmap(target.explicitId(), uidArray);
                    videoRenderRecord.updateRenderTask(senderUserId, showcaseRenderTaskByBeatmap.taskId());
                    return showcaseRenderTaskByBeatmap;
                });
            }
            case "ms" -> {
                if (args.length < 1 || args.length > 2) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/ms <铺面集ID 或 快捷查询>"));
                }

                TargetResolution targetResolution = resolveTargetWithOptionalMention(args, platform, senderUserId);
                if (args.length != targetResolution.consumedArgs()) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/ms <铺面集ID 或 快捷查询>"));
                }
                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                return queueApiRequest("ms", () -> PendingMessage.ofImageBase64(APIHelper.getBeatmapSet(target)));
            }
            case "sms" -> {
                if (args.length == 0) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/sms <搜索关键字>"));
                }
                return queueApiRequest("sms", () -> PendingMessage.ofString(APIHelper.searchBeatmapSet(query)));
            }
            case "lb", "c" -> {
                if (args.length == 0) {
                    if (groupId != null && !groupId.isBlank()) {
                        List<Integer> groupBoundUids = UserBindingStore.findBoundUidsByGroup(platform, groupId);
                        if (groupBoundUids.isEmpty()) {
                            return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind <玩家ID>"));
                        }
                        String[] uidArray = groupBoundUids.stream()
                                .map(String::valueOf)
                                .toArray(String[]::new);
                        return queueApiRequest("lb", () -> PendingMessage.ofImageBase64(APIHelper.getLeaderboard(uidArray)));
                    }
                    Integer uid = resolveBoundUid(platform, senderUserId);
                    if (uid == null) {
                        return RouteDecision.sync(PendingMessage.ofString(NO_BIND_TIP));
                    }
                    return queueApiRequest("lb", () -> PendingMessage.ofImageBase64(APIHelper.getLeaderboard(new String[]{String.valueOf(uid)})));
                } else if (args.length == 1 || args.length == 2) {
                    TargetResolution targetResolution = resolveTargetWithOptionalMention(args, platform, senderUserId);
                    ShortcutTarget target = targetResolution.target();
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    int remainingArgs = args.length - targetResolution.consumedArgs();
                    if (remainingArgs == 0) {
                        if (groupId != null && !groupId.isBlank()) {
                            List<Integer> groupBoundUids = UserBindingStore.findBoundUidsByGroup(platform, groupId);
                            if (groupBoundUids.isEmpty()) {
                                return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind <玩家ID>"));
                            }
                            String[] uidArray = groupBoundUids.stream()
                                    .map(String::valueOf)
                                    .toArray(String[]::new);
                            return queueApiRequest("lbm", () -> PendingMessage.ofImageBase64(APIHelper.getGroupLeaderboard(target, uidArray)));
                        }
                        Integer uid = resolveBoundUid(platform, senderUserId);
                        if (uid == null) {
                            return RouteDecision.sync(PendingMessage.ofString(NO_BIND_TIP));
                        }
                        return queueApiRequest("lbm", () -> PendingMessage.ofImageBase64(APIHelper.getGroupLeaderboard(target, new String[]{String.valueOf(uid)})));
                    }

                    if (remainingArgs != 1) {
                        return RouteDecision.sync(PendingMessage.ofString("用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                    }

                    String[] uidTokens = args[targetResolution.consumedArgs()].split(",");
                    if (uidTokens.length == 0) {
                        return RouteDecision.sync(PendingMessage.ofString("玩家ID列表不能为空。用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                    }
                    String[] uidArray = new String[uidTokens.length];
                    for (int i = 0; i < uidTokens.length; i++) {
                        Integer uid = parsePositiveInt(uidTokens[i].trim());
                        if (uid == null) {
                            return RouteDecision.sync(PendingMessage.ofString("玩家ID列表包含非法值。用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                        }
                        uidArray[i] = String.valueOf(uid);
                    }
                    return queueApiRequest("lbm", () -> PendingMessage.ofImageBase64(APIHelper.getGroupLeaderboard(target, uidArray)));
                } else {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                }
            }
            case "status" -> {
                return RouteDecision.sync(PendingMessage.ofString(APIHelper.getServerStatus()));
            }
            case "rstat" -> {
                if (args.length == 1) {
                    return RouteDecision.sync(PendingMessage.ofString(APIHelper.getRenderStat(args[0])));
                } else if (args.length == 0) {
                    if (videoRenderRecord.hasRenderTask(senderUserId)) {
                        return RouteDecision.sync(PendingMessage.ofString(APIHelper.getRenderStat(videoRenderRecord.getRenderTask(senderUserId))));
                    } else {
                        return RouteDecision.sync(PendingMessage.ofString("未找到渲染请求"));
                    }
                } else {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/rstat [任务ID]"));
                }
            }
            case "help" -> {
                return RouteDecision.sync(PendingMessage.ofString("""
                        可用指令：
                        /bind <玩家ID> - 绑定你的玩家ID
                        /unbind - 解除你的玩家ID绑定
                        /bo <个数> [玩家ID/@用户] - 获取BoN图谱
                        /rs <个数> [玩家ID/@用户] - 获取最近成绩图谱
                        /m <铺面ID> - 获取铺面图谱
                        /ms <铺面集ID> - 获取铺面集图谱
                        /r <成绩ID或快捷查询> - 生成成绩回放视频
                        /rsc <铺面ID或快捷查询> [+用户ID列表] - 生成同屏回放视频
                        /rstat [任务ID] - 查询渲染进度
                        /sms <关键字> - 搜索铺面集
                        /c <铺面ID> [玩家ID列表] - 获取指定铺面排行榜
                        /lb [铺面ID] - /c 的别名（省略参数时走绑定用户）
                        /daily - 获取每日挑战
                        /mp - 获取多人房间列表
                        /status - 获取服务器状态
                        /help - 显示此帮助信息"""));
            }
            default -> {
                return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
            }
        }

    }

    private ShortcutTarget parseTarget(String arg, String platform, String senderUserId) {
        return parseTarget(arg, platform, senderUserId, false);
    }

    private ShortcutTarget parseTarget(String arg, String platform, String senderUserId, boolean mentionedUser) {
        Matcher setMatcher = SET_MACRO_PATTERN.matcher(arg.trim());
        if (setMatcher.matches()) {
            Long setId = parsePositiveLong(setMatcher.group(1));
            Long index = parsePositiveLong(setMatcher.group(2));

            if (setId == null || index == null || index < 1) {
                return new ShortcutTarget(null, null, null, null, "铺面集索引无效。例如: 12345#2");
            }

            Integer uid = resolveBoundUid(platform, senderUserId);

            return new ShortcutTarget(setId, uid, "ms", index, null);
        }

        Matcher userMatcher = USER_MACRO_PATTERN.matcher(arg.trim());
        if (userMatcher.matches()) {
            String type = userMatcher.group(1).toLowerCase();
            Long index = parsePositiveLong(userMatcher.group(2));

            if (index == null || index < 1 || index > 100) {
                return new ShortcutTarget(null, null, null, null, "快捷指令索引无效，请输入 1-100 之间的数字。例如: rs5");
            }

            Integer uid = resolveBoundUid(platform, senderUserId);
            if (uid == null) {
                String errorMessage = mentionedUser
                        ? "被@的用户还没有绑定玩家ID，无法使用快捷查询。"
                        : "你还没有绑定玩家ID，无法使用快捷查询。请先使用 /bind <玩家ID>";
                return new ShortcutTarget(null, null, null, null, errorMessage);
            }

            return new ShortcutTarget(null, uid, type, index, null);
        }

        Matcher beatmapMatcher = BEATMAP_MACRO_PATTERN.matcher(arg.trim());
        if (beatmapMatcher.matches()) {
            Long mapId = parsePositiveLong(beatmapMatcher.group(1));
            Integer uid = resolveBoundUid(platform, senderUserId);
            return new ShortcutTarget(mapId, uid, "m", null, null);
        }

        Long id = parsePositiveLong(arg);
        if (id == null) {
            return new ShortcutTarget(null, null, null, null, "参数无效。请输入纯数字ID或快捷指令 (例如 rs1, 12345#2)。");
        }

        return new ShortcutTarget(id, null, null, null, null);
    }

    private TargetResolution resolveTargetWithOptionalMention(String[] args, String platform, String senderUserId) {
        if (args.length >= 2 && isUserMacro(args[1])) {
            String mentionedUserId = extractMentionedUserId(args[0]);
            if (mentionedUserId != null) {
                return new TargetResolution(parseTarget(args[1], platform, mentionedUserId, true), 2);
            }
            if (looksLikeMention(args[0])) {
                return new TargetResolution(new ShortcutTarget(null, null, null, null, "@用户格式无效，请使用@用户后再输入快捷查询（如 rs2）。"), 2);
            }
        }
        return new TargetResolution(parseTarget(args[0], platform, senderUserId), 1);
    }

    private boolean isUserMacro(String arg) {
        return USER_MACRO_PATTERN.matcher(arg.trim()).matches();
    }

    private boolean looksLikeMention(String token) {
        String trimmed = token == null ? "" : token.trim();
        return trimmed.startsWith("@") || trimmed.startsWith("[CQ:at,");
    }

    private String extractMentionedUserId(String token) {
        if (token == null) {
            return null;
        }

        String trimmed = token.trim();
        Matcher cqMatcher = CQ_AT_PATTERN.matcher(trimmed);
        if (cqMatcher.matches()) {
            return cqMatcher.group(1);
        }

        Matcher plainMatcher = PLAIN_AT_PATTERN.matcher(trimmed);
        if (plainMatcher.matches()) {
            return plainMatcher.group(1);
        }

        return null;
    }

    private UidResolution resolveUidArgument(String arg, String platform) {
        Integer explicitUid = parsePositiveInt(arg);
        if (explicitUid != null) {
            return new UidResolution(explicitUid, null);
        }

        String mentionedUserId = extractMentionedUserId(arg);
        if (mentionedUserId != null) {
            Integer boundUid = resolveBoundUid(platform, mentionedUserId);
            if (boundUid == null) {
                return new UidResolution(null, "被@的用户还没有绑定玩家ID，请先让对方使用 /bind <玩家ID>");
            }
            return new UidResolution(boundUid, null);
        }

        if (looksLikeMention(arg)) {
            return new UidResolution(null, "@用户格式无效，请使用 @用户 后再输入指令。示例：/bo 5 @123456");
        }

        return new UidResolution(null, null);
    }

    private UidListResolution resolveRscUidList(String platform, String groupId, String extraUidArg) {
        List<Integer> groupBoundUids = UserBindingStore.findBoundUidsByGroup(platform, groupId);
        if (groupBoundUids.isEmpty()) {
            return new UidListResolution(null, "本群还没有已绑定的玩家，请先使用 /bind <玩家ID>");
        }

        Set<String> merged = new LinkedHashSet<>();
        groupBoundUids.stream().map(String::valueOf).forEach(merged::add);

        if (extraUidArg != null) {
            String trimmed = extraUidArg.trim();
            if (!trimmed.startsWith("+")) {
                return new UidListResolution(null, "追加用户ID列表必须以 + 开头。" + RSC_USAGE);
            }
            String body = trimmed.substring(1).trim();
            if (body.isEmpty()) {
                return new UidListResolution(null, "追加用户ID列表不能为空。" + RSC_USAGE);
            }

            String[] extraTokens = body.split(",");
            for (String token : extraTokens) {
                Integer uid = parsePositiveInt(token.trim());
                if (uid == null) {
                    return new UidListResolution(null, "追加用户ID列表包含非法值。" + RSC_USAGE);
                }
                merged.add(String.valueOf(uid));
            }
        }

        return new UidListResolution(merged.toArray(String[]::new), null);
    }

    private RouteDecision queueReplayTask(String requestType, ReplayTaskCreator creator) {
        AtomicReference<APIHelper.ReplayTaskInfo> taskInfoRef = new AtomicReference<>();

        return queueApiRequestUntilSubmit(
                requestType,
                () -> {
                    APIHelper.ReplayTaskInfo taskInfo = creator.create();
                    taskInfoRef.set(taskInfo);

                    String queuedText = "生成请求已提交。";
                    if (taskInfo.position() != null) {
                        queuedText += "\n队列位置：" + taskInfo.position();
                    }
                    if (taskInfo.taskId() != null) {
                        queuedText += "\n请求ID：" + taskInfo.taskId();
                    }
                    if (taskInfo.message() != null) {
                        queuedText += "\n" + taskInfo.message();
                    }
                    return PendingMessage.ofString(queuedText);
                },
                () -> {
                    APIHelper.ReplayTaskInfo taskInfo = taskInfoRef.get();
                    if (taskInfo == null || taskInfo.taskId() == null || taskInfo.taskId().isBlank()) {
                        return PendingMessage.ofString("回放任务未返回有效请求ID，无法获取视频结果。请稍后重试。");
                    }

                    APIHelper.ReplayRenderResult result = APIHelper.waitReplayVideo(taskInfo.taskId());

                    if (result != null) {
                        return PendingMessage.ofVideoUrl(result.videoUrl());
                    } else {
                        return PendingMessage.ofString("回放视频生成失败，请稍后重试。");
                    }
                },
                () -> {}
        );
    }

    private RouteDecision queueApiRequest(String requestType, ApiTaskExecutor executor) {
        return queueApiRequest(requestType, executor, () -> null, () -> {
        });
    }

    private RouteDecision queueApiRequest(String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor, ApiTaskFinalizer finalizer) {
        long estimatedSeconds = API_REQUEST_STATS.estimateAndEnqueue(requestType);
        PendingMessage queuedNotice = PendingMessage.ofString("请求已加入队列，预计等待时间" + estimatedSeconds + "秒。");
        return RouteDecision.async(queuedNotice, new ApiTask(requestType, executor, postProcessor, finalizer, false));
    }

    private RouteDecision queueApiRequestUntilSubmit(String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor, ApiTaskFinalizer finalizer) {
        long estimatedSeconds = API_REQUEST_STATS.estimateAndEnqueue(requestType);
        PendingMessage queuedNotice = PendingMessage.ofString("请求已加入队列，预计等待时间" + estimatedSeconds + "秒。");
        return RouteDecision.async(queuedNotice, new ApiTask(requestType, executor, postProcessor, finalizer, true));
    }

    private void processApiTask(String targetId, String messageId, boolean groupMessage, ApiTask apiTask, AtomicInteger messageSeqCounter) {
        long startedAt = System.nanoTime();
        boolean statsCompleted = false;
        try {
            PendingMessage response = apiTask.executor().execute();
            if (response != null) {
                sendOutboundMessage(targetId, messageId, groupMessage, response, messageSeqCounter);
            }

            if (apiTask.completeStatsAfterExecutor()) {
                long elapsedMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
                API_REQUEST_STATS.complete(apiTask.requestType(), elapsedMillis);
                statsCompleted = true;
            }

            PendingMessage postResponse = apiTask.postProcessor().execute();
            if (postResponse != null) {
                sendOutboundMessage(targetId, messageId, groupMessage, postResponse, messageSeqCounter);
            }
        } catch (Exception e) {
            sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString(resolveErrorMessage(e)), messageSeqCounter);
            LOG.error("Failed to execute API task for message {}", messageId, e);
        } finally {
            try {
                apiTask.finalizer().execute();
            } catch (Exception e) {
                LOG.warn("Failed to run finalizer for message {}", messageId, e);
            }
            if (!statsCompleted) {
                long elapsedMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
                API_REQUEST_STATS.complete(apiTask.requestType(), elapsedMillis);
            }
        }
    }

    private String resolveErrorMessage(Exception exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            if (cursor instanceof ApiRequestException apiRequestException) {
                String mapped = mapErrorCodeMessage(apiRequestException.getErrorCode());
                if (mapped != null) {
                    return mapped;
                }

                String rawMessage = apiRequestException.getMessage();
                if (rawMessage != null && !rawMessage.isBlank()) {
                    return rawMessage;
                }
            }
            cursor = cursor.getCause();
        }
        return "请求处理失败，请稍后再试。";
    }

    private String mapErrorCodeMessage(Integer code) {
        ErrorCode errorCode = ErrorCode.fromCode(code);
        if (errorCode == null) {
            return null;
        }

        return switch (errorCode) {
            case NO_BEATMAP_FOUND -> "未找到对应铺面，请检查输入后重试。";
            case NO_BEATMAPSET_FOUND -> "未找到对应铺面集，请检查输入后重试。";
            case NO_USER_FOUND -> "未找到对应玩家，请检查玩家ID后重试。";
            case NO_SCORE_FOUND -> "未找到对应成绩，请检查输入后重试。";
            case NO_ROOM_FOUND -> "当前没有可用的多人房间信息。";
            case ILLEGAL_ARGUMENT -> "请求参数不合法，请检查指令参数格式。";
            case BEATMAP_FETCH_FAILED -> "获取铺面数据失败，请稍后重试。";
            case BEATMAPSET_FETCH_FAILED -> "获取铺面集数据失败，请稍后重试。";
            case USER_FETCH_FAILED -> "获取玩家数据失败，请稍后重试。";
            case SCORE_FETCH_FAILED -> "获取成绩数据失败，请稍后重试。";
            case REPLAY_UNAVAILABLE -> "该成绩暂不支持回放渲染。";
            case RENDER_QUEUE_FULL -> "回放渲染队列已满，请稍后再试。";
        };
    }

    private void sendOutboundMessage(String targetId, String messageId, boolean groupMessage, PendingMessage pendingMsg, AtomicInteger messageSeqCounter) {
        Message message = new Message();
        message.setMsgType(pendingMsg.getMsgType());
        message.setMsgId(messageId);
        message.setMsgSeq(messageSeqCounter.getAndIncrement());

        if (pendingMsg.getMsgType() == PendingMessage.MSG_TYPE_MARKDOWN) {
            message.setMarkdown(new Gson().toJsonTree(Map.of("content", pendingMsg.getContent())).getAsJsonObject());
        } else {
            message.setContent(pendingMsg.getContent());
        }

        if (pendingMsg.getFileUrl() != null) {
            LOG.info("Uploading media for {}", messageId);
            FileInfo fileInfo = groupMessage
                    ? messageSender.uploadGroupMedia(targetId, pendingMsg.getFileType(), pendingMsg.getFileUrl())
                    : messageSender.uploadPrivateMedia(targetId, pendingMsg.getFileType(), pendingMsg.getFileUrl());
            if (fileInfo == null) {
                LOG.error("Failed to upload media for message {}", messageId);
                message.setContent("媒体文件上传失败");
                message.setMsgType(0);
            } else {
                LOG.info("Media uploaded for message {}", messageId);
                message.setMedia(fileInfo);
            }
        } else if (pendingMsg.getFileBase64() != null) {
            FileInfo fileInfo = groupMessage
                    ? messageSender.uploadGroupMediaBase64(targetId, pendingMsg.getFileType(), pendingMsg.getFileBase64())
                    : messageSender.uploadPrivateMediaBase64(targetId, pendingMsg.getFileType(), pendingMsg.getFileBase64());
            if (fileInfo == null) {
                LOG.error("Failed to upload base64 media for message {}", messageId);
                message.setContent("媒体文件上传失败");
                message.setMsgType(0);
            } else {
                LOG.info("Base64 media uploaded for message {}", messageId);
                message.setMedia(fileInfo);
            }
        }

        if (groupMessage) {
            messageSender.sendGroupMessage(targetId, message);
        } else {
            messageSender.sendPrivateMessage(targetId, message);
        }
    }

    private Integer resolveBoundUid(String platform, String senderUserId) {
        if (senderUserId == null || senderUserId.isBlank()) {
            return null;
        }
        return UserBindingStore.findBoundUid(platform, senderUserId);
    }

    private Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ApiTaskExecutor {
        PendingMessage execute();
    }

    @FunctionalInterface
    private interface ReplayTaskCreator {
        APIHelper.ReplayTaskInfo create();
    }

    @FunctionalInterface
    private interface ApiTaskPostProcessor {
        PendingMessage execute();
    }

    @FunctionalInterface
    private interface ApiTaskFinalizer {
        void execute();
    }

    private record ApiTask(String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor,
                           ApiTaskFinalizer finalizer, boolean completeStatsAfterExecutor) {
    }

    private record TargetResolution(ShortcutTarget target, int consumedArgs) {
    }

    private record UidResolution(Integer uid, String errorMessage) {
    }

    private record UidListResolution(String[] uids, String errorMessage) {
    }

    protected record RouteDecision(PendingMessage initialMessage, ApiTask apiTask) {
        private static RouteDecision sync(PendingMessage message) {
            return new RouteDecision(message, null);
        }

        private static RouteDecision async(PendingMessage message, ApiTask apiTask) {
            return new RouteDecision(message, apiTask);
        }
    }
}

