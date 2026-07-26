package xyz.zcraft.seira.command;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.*;
import xyz.zcraft.seira.binding.BindingHelper;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.resolution.ShortcutTarget;
import xyz.zcraft.seira.command.resolution.TargetResolution;
import xyz.zcraft.seira.command.resolution.RscTarget;
import xyz.zcraft.seira.command.resolution.UidResolution;
import xyz.zcraft.seira.command.route.DebugRoutes;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.services.DailyLuck;
import xyz.zcraft.seira.util.OsuAuthHelper;
import xyz.zcraft.seira.util.ThreadHelper;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class Router {
    private static final Logger LOG = LogManager.getLogger(Router.class);

    private static final String PREFIX = "/";
    public final MessageSender messageSender;
    public final AppConfig config;
    public final ReplyFactory replyFactory;
    public final TaskCoordinator taskCoordinator;
    public final OsuAuthHelper authHelper;
    private final VideoRenderRecord videoRenderRecord = new VideoRenderRecord();
    @Getter
    private final ConcurrentHashMap<String, APIHelper.ReplayRenderResult> renderResults = new ConcurrentHashMap<>();
    private final Resolver resolver;
    private final DebugRoutes debugRoutes;

    private final ConcurrentHashMap<String, ShortcutTarget> lastTarget = new ConcurrentHashMap<>();

    public Router(MessageSender messageSender, AppConfig config) {
        this.messageSender = messageSender;
        this.config = config;
        this.resolver = new Resolver();
        this.replyFactory = new ReplyFactory(config);
        this.taskCoordinator = new TaskCoordinator(this, messageSender);
        this.authHelper = new OsuAuthHelper(config.binding());

        this.debugRoutes = new DebugRoutes(this);
    }

    public void onPrivateMessageReceived(String userId, String messageId, String rawContent) {
        handleMessageReceived(userId, null, userId, messageId, rawContent, false);
    }

    public void onGroupMessageReceived(String groupId, String senderUserId, String messageId, String rawContent) {
        handleMessageReceived(groupId, groupId, senderUserId, messageId, rawContent, true);
    }

    private void handleMessageReceived(String targetId, String groupId, String userId, String messageId, String rawContent, boolean groupMessage) {
        LOG.info("Received {} message : {}", groupMessage ? "group" : "private", rawContent);
        AtomicInteger messageSeqCounter = new AtomicInteger(1);
        try {
            final boolean group = groupMessage && groupId != null && !groupId.isBlank();
            if (group && userId != null && !userId.isBlank()) {
                UserDataStore.upsertGroupMember(groupId, userId);
            }

            RouteDecision routeDecision = route(rawContent, userId, groupId, messageId);
            if (routeDecision == null) {
                return;
            }

            if (routeDecision.initialMessage() != null) {
                if (!routeDecision.enqueueMessage() || !group || config.seira().queueMessageInGroup()) {
                    boolean res = taskCoordinator.sendOutboundMessage(
                            targetId, messageId, groupMessage,
                            routeDecision.initialMessage(), messageSeqCounter
                    );

                    if (routeDecision.onSent() != null) {
                        routeDecision.onSent().accept(res);
                    }
                }
            }

            ApiTask apiTask = routeDecision.apiTask();
            if (apiTask != null) {
                ThreadHelper.run(() -> taskCoordinator.processApiTask(targetId, messageId, groupMessage, apiTask, messageSeqCounter));
            }
        } catch (Exception e) {
            taskCoordinator.sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString("处理指令时发生错误，请稍后再试。"), messageSeqCounter);
            LOG.error("Failed to process inbound message {}", messageId, e);
        }
    }

    protected RouteDecision route(String rawContent, String senderUserId, String groupId, String messageId) {
        if (rawContent == null || !rawContent.trim().startsWith(PREFIX)) {
            return null;
        }

        String body = rawContent.trim().substring(PREFIX.length()).trim();
        if (body.isEmpty()) {
            return RouteDecision.sync(PendingMessage.ofString("请输入指令。使用/help获取帮助。"));
        }

        body = resolver.preProcess(body);

        String[] parts = body.split("\\s+");
        String command = parts[0].toLowerCase();
        String query = body.substring(command.length()).trim();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        BotStat.incrementCommands();

        final Context ctx = new Context(senderUserId, groupId, messageId, command, args, query);

        if (command.startsWith("debug.")) {
            return debugRoutes.routeDebug(ctx);
        }

        return switch (command) {
            case "bind" -> handleBind(ctx);
            case "unbind" -> handleUnbind(ctx);
            case "clearhistory" -> handleClearHistory(ctx);
            case "bp", "bo" -> handleBo(ctx);
            case "daily" -> handleDaily(ctx);
            case "mp" -> handleMp(ctx);
            case "rs" -> handleRs(ctx, true);
            case "rp" -> handleRs(ctx, false);
            case "m" -> handleM(ctx);
            case "ap" -> handleAp(ctx);
            case "bgp" -> handleBgp(ctx);
            case "f" -> handleF(ctx, !ctx.inGroup());
            case "fall" -> handleF(ctx, true);
            case "fclear" -> handleFclear(ctx);
            case "dl" -> handleDl(ctx);
            case "s" -> handleS(ctx);
            case "sa" -> handleSa(ctx);
            case "ma" -> handleMa(ctx);
            case "r" -> handleR(ctx);
            case "rsc" -> handleRsc(ctx);
            case "ms" -> handleMs(ctx);
            case "sms" -> handleSms(ctx);
            case "lb" -> handleLb(ctx);
            case "stat" -> handleStat(ctx);
            case "u" -> handleU(ctx);
            case "luck" -> handleLuck(ctx);
            case "rstat" -> handleRstat(ctx);
            case "inspect" -> handleInspect(ctx);
            case "help" -> handleHelp(ctx);
            case "faq" -> handleFaq(ctx);
            default -> handleUnknown();
        };
    }

    private RouteDecision handleBind(Context ctx) {
        if (ctx.senderUserId() == null || ctx.senderUserId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法绑定。请稍后重试。"));
        }

        if (UserDataStore.findBoundUid(ctx.senderUserId()) != null) {
            return RouteDecision.sync(PendingMessage.ofString("你已经绑定了玩家ID，如果要更换绑定请先使用 /unbind 解绑当前玩家ID。"));
        }

        if (ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/bind"));
        }

        final var bindingTask = BindingHelper.createBindingTask(ctx.senderUserId(), ctx.messageId(), (user, token) -> {
            UserDataStore.bind(ctx.senderUserId(), user.getId());
            UserDataStore.storeToken(ctx.senderUserId(), token);
            UserDataStore.storeUserInfo(user.getId(), user.getUsername());
        });

        return RouteDecision.sync(replyFactory.bindMessage(ctx, config.binding(), bindingTask,
                ctx.groupId() == null || ctx.groupId().isBlank()));
    }

    private RouteDecision handleUnbind(Context ctx) {
        if (ctx.senderUserId() == null || ctx.senderUserId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法解绑。请稍后重试。"));
        }
        if (ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/unbind"));
        }
        boolean removed = UserDataStore.unbind(ctx.senderUserId());
        return RouteDecision.sync(PendingMessage.ofString(removed
                ? "解绑成功。"
                : "你当前还没有绑定玩家ID，无需解绑。"));
    }

    private RouteDecision handleClearHistory(Context ctx) {
        if (ctx.senderUserId() == null || ctx.senderUserId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，无法清除历史记录。请稍后重试。"));
        }
        if (ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/clearhistory"));
        }
        int removed = UserDataStore.clearGroupMember(ctx.senderUserId());
        return RouteDecision.sync(PendingMessage.ofString("清除了 " + removed + " 条群聊记录。"));
    }

    private RouteDecision handleBo(Context ctx) {
        if (ctx.args().length == 2) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
            }
            UidResolution uidResolution = resolver.resolveUidArgument(ctx.args()[1]);
            if (uidResolution.errorMessage() != null) {
                return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
            }
            if (uidResolution.uid() == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
            }
            var uid = uidResolution.uid();

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, uid),
                    replyFactory::boMessage
            );
        } else if (ctx.args().length == 1) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, uid),
                    replyFactory::boMessage
            );
        } else if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget("bo1", ctx.senderUserId());
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
        }
    }

    private RouteDecision handleDaily(Context ctx) {
        return taskCoordinator.queueApiRequest(ctx, "Daily Challenge", () -> PendingMessage.ofMarkdownRaw(APIHelper.getDaily()));
    }

    private RouteDecision handleMp(Context ctx) {
        if (resolver.resolveBoundUid(ctx.senderUserId()) == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
        }

        OsuToken token = authHelper.updateTokenAndGet(ctx.senderUserId());

        if (token == null) {
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(Usages.REBIND_TIP));
        }

        return taskCoordinator.queueApiRequest(ctx, "Multiplayer Room",
                () -> replyFactory.mpMessage(ctx, APIHelper.getMultiplayerRoom(token.accessToken())));
    }

    private RouteDecision handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 2) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
            }

            UidResolution uidResolution = resolver.resolveUidArgument(ctx.args()[1]);
            if (uidResolution.errorMessage() != null) {
                return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
            }

            if (uidResolution.uid() == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, uidResolution.uid(), includeFail),
                    replyFactory::rsMessage
            );
        } else if (ctx.args().length == 1) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, uid, includeFail),
                    replyFactory::rsMessage
            );
        } else if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget(ctx.command() + "1", ctx.senderUserId());
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
        }
    }

    private RouteDecision handleM(Context ctx) {
        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);

            if (ctx.args().length > targetResolution.consumedArgs() + 1) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.M_USAGE));
            }

            String mod = ctx.args().length == targetResolution.consumedArgs() + 1
                    ? ctx.args()[targetResolution.consumedArgs()]
                    : null;

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Beatmap",
                    () -> APIHelper.getBeatmapResponse(target, mod, getAccessTokenFor(ctx.senderUserId())),
                    replyFactory::beatmapMessage
            );
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                ShortcutTarget target = lastTarget.get(ctx.senderUserId());
                return taskCoordinator.queueImageRequest(
                        ctx,
                        "Beatmap",
                        () -> APIHelper.getBeatmapResponse(target, null, getAccessTokenFor(ctx.senderUserId())),
                        replyFactory::beatmapMessage
                );
            }

            return RouteDecision.sync(PendingMessage.ofString(Usages.M_USAGE));
        }
    }

    private RouteDecision handleAp(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                target = lastTarget.get(ctx.senderUserId());
            } else return RouteDecision.sync(PendingMessage.ofString(Usages.AP_USAGE));
        }

        return taskCoordinator.queueApiRequest(
                ctx,
                "Audio Preview",
                () -> {
                    final long id = APIHelper.lookupBeatmapset(target, getAccessTokenFor(ctx.senderUserId()));
                    return PendingMessage.ofVoiceUrl("https://b.ppy.sh/preview/" + id + ".mp3").doUpload(false);
                }
        );
    }

    private RouteDecision handleBgp(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                target = lastTarget.get(ctx.senderUserId());
            } else return RouteDecision.sync(PendingMessage.ofString(Usages.BGP_USAGE));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Background Preview",
                () -> APIHelper.getBeatmapBgResponse(target, getAccessTokenFor(ctx.senderUserId())),
                replyFactory::bgpMessage
        );
    }

    private RouteDecision handleF(Context ctx, boolean all) {
        final Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
        }

        OsuToken token = authHelper.updateTokenAndGet(ctx.senderUserId());

        if (token == null) {
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(Usages.REBIND_TIP));
        }

        return taskCoordinator.queueApiRequest(ctx, "Friend List", () -> {
            final Response<UserExtended> self = APIHelper.getSelf(token.accessToken());
            final Response<List<FriendEntry>> response = APIHelper.getFollowed(token.accessToken());
            final List<FriendEntry> content = response.getContent();
            final List<Long> ids = content.stream().map(e -> e.user().getId()).toList();

            final Predicate<Long> filter;
            if (ctx.inGroup() && !all) {
                final var groupIds = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                filter = groupIds::contains;
            } else {
                filter = (_) -> true;
            }

            UserDataStore.storeUserInfo(self.getContent().getId(), self.getContent().getUsername());
            response.getContent().stream()
                    .map(FriendEntry::user)
                    .forEach(u -> UserDataStore.storeUserInfo(u.getId(), u.getUsername()));

            final List<Long> origFollower = UserDataStore.findFollower(uid);

            origFollower.stream()
                    .filter(i -> !ids.contains(i))
                    .forEach(i -> UserDataStore.removeFollowed(uid, i));

            for (FriendEntry friendEntry : content) {
                if (!UserDataStore.haveFollowed(uid, friendEntry.user().getId())) {
                    UserDataStore.storeFollowed(uid, friendEntry.user().getId());
                }

                if (friendEntry.mutual()) {
                    if (!UserDataStore.haveFollowed(friendEntry.user().getId(), uid)) {
                        UserDataStore.storeFollowed(friendEntry.user().getId(), uid);
                    }
                }
            }

            final List<Long> follower = UserDataStore.findFollower(uid);

            final List<User> mutual = new LinkedList<>();
            final List<User> onlyFollowed = new LinkedList<>();
            final List<User> onlyFollower = new LinkedList<>();

            for (FriendEntry e : content) {
                if (!filter.test(e.user().getId())) continue;
                if (follower.contains(e.user().getId())) {
                    mutual.add(e.user());
                } else {
                    onlyFollowed.add(e.user());
                }
            }

            for (Long i : follower) {
                if (!filter.test(i)) continue;
                if (content.stream().noneMatch(entry -> Objects.equals(entry.user().getId(), i))) {
                    User u = new User();
                    u.setId(i);
                    u.setUsername(UserDataStore.findUsername(i).orElse("未知-" + i));
                    onlyFollower.add(u);
                }
            }

            long allMutualCount = content.stream().filter(FriendEntry::mutual).count();

            final Comparator<User> userComparator = Comparator.comparing(User::isOnline, Comparator.reverseOrder()).thenComparing(User::getUsername);
            mutual.sort(userComparator);
            onlyFollower.sort(userComparator);
            onlyFollowed.sort(userComparator);

            return replyFactory.friendMessage(ctx, all, self.getContent(), content.size(), allMutualCount, mutual, onlyFollowed, onlyFollower);
        });
    }

    private RouteDecision handleFclear(Context ctx) {
        Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
        }

        return RouteDecision.sync(PendingMessage.ofString(
                "已清除 " + UserDataStore.clearFollowed(uid) + " 条好友记录。"
        ));
    }

    private RouteDecision handleDl(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.DL_USAGE));
            }

            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.DL_USAGE));
        }

        return taskCoordinator.queueApiRequest(
                ctx,
                "Download Beatmap",
                () -> replyFactory.dlMessage(
                        ctx,
                        APIHelper.getLookupBeatmapsetResponse(target, getAccessTokenFor(ctx.senderUserId()))
                )
        );
    }

    private RouteDecision handleS(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());

            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.S_USAGE));
            }

            target = targetResolution.target();

            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.S_USAGE));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Score",
                () -> APIHelper.getScoreResponse(target),
                replyFactory::scoreMessage
        );
    }

    private RouteDecision handleSa(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());

            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.SA_USAGE));
            }

            target = targetResolution.target();

            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.SA_USAGE));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Score Analysis",
                () -> APIHelper.getScoreAnalyzeResponse(target),
                replyFactory::scoreAnalyzeMessage
        );
    }

    private RouteDecision handleMa(Context ctx) {
        TargetResolution targetResolution = resolveOptionalTarget(ctx, arg -> arg.startsWith("#"));
        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.MA_USAGE));
        }
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        int remainingArgs = ctx.args().length - targetResolution.consumedArgs();
        if (remainingArgs > 1) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.MA_USAGE));
        }

        if (remainingArgs == 1) {
            Integer index = parseMissIndex(
                    ctx.args()[targetResolution.consumedArgs()],
                    targetResolution.consumedArgs() == 0
            );
            if (index == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.MA_USAGE));
            }

            rememberExplicitTarget(ctx, targetResolution);

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Miss Visualize",
                    () -> APIHelper.getMissVisualizeResponse(target, index),
                    (_, _) -> null
            );
        }

        rememberExplicitTarget(ctx, targetResolution);

        return taskCoordinator.queueApiRequest(
                ctx,
                "Get Score Misses",
                () -> replyFactory.scoreMissesMessage(ctx, APIHelper.getScoreMissesResponse(target))
        );
    }

    private RouteDecision handleR(Context ctx) {
        TargetResolution targetResolution = resolveOptionalTarget(ctx, TimeDurationParser::isTimeRange);
        if (ctx.args().length - targetResolution.consumedArgs() > 1) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.R_USAGE));
        }

        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.R_USAGE));
        }
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        TimeDurationParser.TimeRange range = null;

        if (ctx.args().length > targetResolution.consumedArgs()) {
            try {
                range = TimeDurationParser.parseRange(ctx.args()[targetResolution.consumedArgs()]);
            } catch (IllegalArgumentException e) {
                return RouteDecision.sync(PendingMessage.ofString("无法解析时间范围"));
            }
        }

        rememberExplicitTarget(ctx, targetResolution);

        TimeDurationParser.TimeRange finalRange = range;
        return taskCoordinator.queueReplayTask(
                ctx,
                "Score Render",
                () -> {
                    APIHelper.ReplayTaskInfo task = APIHelper.createReplayRenderTask(target, finalRange);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    private RouteDecision handleRsc(Context ctx) {
        if (ctx.groupId() == null || ctx.groupId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("/rsc 仅支持群聊使用。"));
        }

        TargetResolution targetResolution = resolveOptionalTarget(
                ctx,
                arg -> arg.startsWith("+") || TimeDurationParser.isTimeRange(arg)
        );
        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
        }
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        String extraUidArg = null;

        for (int i = targetResolution.consumedArgs(); i < ctx.args().length; i++) {
            if (ctx.args()[i].startsWith("+") || ctx.args()[i].startsWith("=")) {
                if (extraUidArg != null) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
                }
                extraUidArg = ctx.args()[i];
            } else {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
            }
        }

        RscTarget rscTarget = resolver.resolveRscTarget(ctx.groupId(), extraUidArg);
        if (rscTarget.errorMessage() != null) {
            return RouteDecision.sync(PendingMessage.ofString(rscTarget.errorMessage()));
        }

        String[] uidArray = rscTarget.uids();

        rememberExplicitTarget(ctx, targetResolution);

        return taskCoordinator.queueReplayTask(
                ctx,
                "Showcase Render",
                () -> {
                    var task = APIHelper.createReplayShowcaseTask(target, uidArray, getAccessTokenFor(ctx.senderUserId()));
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    private TargetResolution resolveOptionalTarget(Context ctx, Predicate<String> isOptionalArgument) {
        if (ctx.args().length == 0 || isOptionalArgument.test(ctx.args()[0])) {
            return new TargetResolution(lastTarget.get(ctx.senderUserId()), 0);
        }
        return resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
    }

    private void rememberExplicitTarget(Context ctx, TargetResolution targetResolution) {
        if (targetResolution.consumedArgs() > 0) {
            lastTarget.put(ctx.senderUserId(), targetResolution.target());
        }
    }

    private Integer parseMissIndex(String arg, boolean requirePrefix) {
        String value = arg;
        if (arg.startsWith("#")) {
            value = arg.substring(1);
        } else if (requirePrefix) {
            return null;
        }
        return resolver.parsePositiveInt(value);
    }

    private RouteDecision handleMs(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
            }
            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Beatmapset",
                () -> APIHelper.getBeatmapsetResponse(target, getAccessTokenFor(ctx.senderUserId())),
                replyFactory::beatmapsetMessage
        );
    }

    private RouteDecision handleSms(Context ctx) {
        final SearchQuery searchQuery = resolver.resolveSearchQuery(ctx.query());
        if (searchQuery == null) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/sms [#页数] <搜索关键字>"));
        }
        return taskCoordinator.queueApiRequest(
                ctx,
                "Search Beatmapset",
                () -> {
                    Response<List<SearchResultItem>> searchResponse = APIHelper.searchBeatmapSetResponse(searchQuery);
                    return replyFactory.searchMessage(ctx, searchResponse, searchQuery);
                });
    }

    private RouteDecision handleLb(Context ctx) {
        if (ctx.args().length == 0) {
            if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
                List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                if (groupBoundUids.isEmpty()) {
                    return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind"));
                }

                return taskCoordinator.queueImageRequest(
                        ctx,
                        "Leaderboard",
                        () -> APIHelper.getLeaderboardResponse(groupBoundUids),
                        replyFactory::lbMessage
                );
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Leaderboard",
                    () -> APIHelper.getLeaderboardResponse(List.of(uid)),
                    replyFactory::lbMessage
            );
        } else if (ctx.args().length == 1 || ctx.args().length == 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            int remainingArgs = ctx.args().length - targetResolution.consumedArgs();
            if (remainingArgs == 0) {
                if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
                    List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                    if (groupBoundUids.isEmpty()) {
                        return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind"));
                    }
                    return taskCoordinator.queueImageRequest(
                            ctx,
                            "Map Leaderboard",
                            () -> APIHelper.getGroupLeaderboardResponse(target, groupBoundUids, getAccessTokenFor(ctx.senderUserId())),
                            replyFactory::lbMessage
                    );
                }
                Long uid = resolver.resolveBoundUid(ctx.senderUserId());
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
                }

                return taskCoordinator.queueImageRequest(
                        ctx,
                        "Map Leaderboard",
                        () -> APIHelper.getGroupLeaderboardResponse(target, List.of(uid), getAccessTokenFor(ctx.senderUserId())),
                        replyFactory::lbMessage
                );
            }

            if (remainingArgs != 1) {
                return RouteDecision.sync(PendingMessage.ofString("用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
            }

            String[] uidTokens = ctx.args()[targetResolution.consumedArgs()].split(",");
            if (uidTokens.length == 0) {
                return RouteDecision.sync(PendingMessage.ofString("玩家ID列表不能为空。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
            }

            List<Long> uids = new LinkedList<>();
            for (String uidToken : uidTokens) {
                Long uid = resolver.parsePositiveLong(uidToken.trim());
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString("玩家ID列表包含非法值。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                }
                uids.add(uid);
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Map Leaderboard",
                    () -> APIHelper.getGroupLeaderboardResponse(target, uids, getAccessTokenFor(ctx.senderUserId())),
                    replyFactory::lbMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString("用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
        }
    }

    private RouteDecision handleU(Context ctx) {
        // TODO: 暂时使用bo8
        if (ctx.args().length == 1) {
            return route("/bo 8 " + ctx.args()[0], ctx.senderUserId(), ctx.groupId(), ctx.messageId());
        } else {
            return RouteDecision.sync(PendingMessage.ofString("用法：/u <玩家ID>"));
        }
    }

    private RouteDecision handleLuck(Context ctx) {
        if (ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/luck"));
        }

        return taskCoordinator.queueApiRequest(
                ctx,
                "Luck",
                () -> {
                    final DailyLuck.Luck luck = DailyLuck.getLuck(ctx.senderUserId());
                    final Beatmapset mapset = APIHelper.getBeatmapsetRaw(luck.dailyMapset());
                    final UploadedImage cover = messageSender.uploadImageToCos(mapset.getCovers().getCover());
                    return replyFactory.luckMessage(ctx, luck, mapset, cover);
                }
        );
    }

    private RouteDecision handleRstat(Context ctx) {
        if (ctx.args().length != 1 && ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/rstat [任务ID]"));
        }

        String jobId;
        if (ctx.args().length == 0) {
            if (videoRenderRecord.hasRenderTask(ctx.senderUserId())) {
                jobId = videoRenderRecord.getRenderTask(ctx.senderUserId());
            } else {
                return RouteDecision.sync(PendingMessage.ofString("未找到渲染请求"));
            }
        } else {
            jobId = ctx.args()[0];
        }

        if (renderResults.containsKey(jobId)) {
            return RouteDecision.sync(PendingMessage.ofVideoUrl(renderResults.get(jobId).videoUrl()), b -> {
                if (b) {
                    renderResults.remove(jobId);
                }
            });
        }

        return RouteDecision.sync(replyFactory.replayStatMessage(ctx, jobId, APIHelper.getRenderStat(jobId)));
    }

    private RouteDecision handleInspect(Context ctx) {
        return RouteDecision.sync(replyFactory.inspectMessage(ctx, ctx.senderUserId(),
                isAdmin(ctx.senderUserId()), ctx.groupId(), ctx.messageId()));
    }

    private RouteDecision handleHelp(Context ctx) {
        return RouteDecision.sync(replyFactory.helpMessage(ctx));
    }

    private RouteDecision handleFaq(Context ctx) {
        return RouteDecision.sync(replyFactory.faqMessage(ctx));
    }

    private RouteDecision handleStat(Context ctx) {
        return RouteDecision.sync(replyFactory.statusMessage(ctx, APIHelper.getServerStatus()));
    }

    public RouteDecision handleUnknown() {
        return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
    }

    private String getAccessTokenFor(String openId) {
        return Optional.ofNullable(authHelper.updateTokenAndGet(openId))
                .map(OsuToken::accessToken)
                .orElse(null);
    }

    public boolean isAdmin(String openId) {
        final List<String> adminIds = config.seira().adminIds();
        if (adminIds == null || adminIds.isEmpty()) {
            return false;
        }
        return adminIds.contains(openId);
    }

    private static final class Usages {
        public static final String BO_USAGE = "用法：/bo <个数> [玩家ID/@用户]";
        public static final String NO_BIND_TIP = "你还没有绑定玩家ID，请先使用 /bind 绑定";
        public static final String REBIND_TIP = "由于发生了一个技术问题，使用此功能需要重新绑定。请使用 `/unbind` 解除绑定，再使用 `/bind` 重新绑定~";
        public static final String RS_USAGE = "用法：/rs <个数> [玩家ID/@用户]";
        public static final String M_USAGE = "用法：/m <谱面ID 或 快捷查询> [Mod]";
        public static final String AP_USAGE = "用法：/ap <谱面ID 或 快捷查询>";
        public static final String BGP_USAGE = "用法：/bgp <谱面ID 或 快捷查询>";
        public static final String DL_USAGE = "用法：/dl <谱面集ID 或 快捷查询>";
        public static final String S_USAGE = "用法：/s <成绩ID 或 快捷查询>";
        public static final String SA_USAGE = "用法：/sa <成绩ID 或 快捷查询>";
        public static final String MA_USAGE = "用法：/ma [成绩ID 或 快捷查询] [序号]；省略目标并指定序号时请使用 #序号";
        private static final String R_USAGE = "用法：/r [成绩ID 或 快捷查询] [[mm:ss]-[mm:ss]]";
        private static final String RSC_USAGE = "用法：/rsc [谱面ID或快捷查询] [+用户ID列表，逗号分隔] [[mm:ss]-[mm:ss]]";
    }
}
