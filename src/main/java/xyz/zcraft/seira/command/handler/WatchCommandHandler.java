package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.User;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.route.RouteDecision;
import xyz.zcraft.seira.watch.ScoreWatchService;
import xyz.zcraft.seira.watch.WatchTarget;
import xyz.zcraft.seira.watch.WatchView;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

public final class WatchCommandHandler {
    private static final int DEFAULT_DURATION_MINUTES = 10;
    private static final int MAX_DURATION_MINUTES = 120;
    private static final String USAGE = "用法：/watch add <玩家ID/用户名/@用户> [分钟]；/watch del [玩家ID/用户名/@用户]；/watch list";

    private final Resolver resolver;
    private final TaskCoordinator taskCoordinator;
    private final ScoreWatchService watchService;
    private final Predicate<String> adminAuthorizer;

    public WatchCommandHandler(Resolver resolver, TaskCoordinator taskCoordinator, ScoreWatchService watchService, Predicate<String> adminAuthorizer) {
        this.resolver = Objects.requireNonNull(resolver);
        this.taskCoordinator = Objects.requireNonNull(taskCoordinator);
        this.watchService = watchService;
        this.adminAuthorizer = Objects.requireNonNull(adminAuthorizer);
    }

    public RouteDecision handleWatch(Context ctx) {
        if (!ctx.inGroup()) {
            return RouteDecision.sync(PendingMessage.ofString("/watch 仅支持群聊使用。"));
        }
        if (watchService == null) {
            return RouteDecision.sync(PendingMessage.ofString("成绩监视服务暂不可用。"));
        }
        if (ctx.argumentCount() == 0) {
            return usage();
        }

        return switch (ctx.argument(0).toLowerCase(Locale.ROOT)) {
            case "add" -> handleAdd(ctx);
            case "del", "delete", "remove" -> handleDelete(ctx);
            case "list" -> handleList(ctx);
            case "now" -> handleNow(ctx);
            default -> usage();
        };
    }

    private RouteDecision handleNow(Context ctx) {
        if (adminAuthorizer.test(ctx.senderUserId())) {
            watchService.pollNow();
            return RouteDecision.sync(PendingMessage.ofString("已触发立即轮询。"));
        } else {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }
    }

    private RouteDecision handleAdd(Context ctx) {
        if (ctx.argumentCount() < 2 || ctx.argumentCount() > 3) {
            return usage();
        }
        int minutes = ctx.argumentCount() == 3
                ? parseDurationMinutes(ctx.argument(2))
                : DEFAULT_DURATION_MINUTES;

        String targetArgument = ctx.argument(1);
        return taskCoordinator.queueApiRequest(ctx, "Add Score Watch", () -> {
            WatchTarget target = resolveTarget(ctx.groupId(), targetArgument);
            watchService.add(ctx.groupId(), target, Duration.ofMinutes(minutes));
            return PendingMessage.ofMarkdownRaw(
                    displayTarget(target) + " 添加监视成功！有效期：" + minutes + "分钟"
            );
        });
    }

    private RouteDecision handleDelete(Context ctx) {
        if (ctx.argumentCount() > 2) {
            return usage();
        }
        if (ctx.argumentCount() == 1) {
            int removed = watchService.removeAll(ctx.groupId());
            return RouteDecision.sync(PendingMessage.ofString(
                    removed == 0
                            ? "当前群聊没有监视任务。"
                            : "已移除当前群聊中的全部监视任务，共 " + removed + " 个。"
            ));
        }

        String targetArgument = ctx.argument(1);
        String mentionedOpenId = resolver.extractMentionedUserId(targetArgument);
        if (mentionedOpenId != null) {
            if (!UserDataStore.isGroupMember(ctx.groupId(), mentionedOpenId)) {
                return RouteDecision.sync(PendingMessage.ofString("指定的用户不在当前群聊中。"));
            }
            WatchView removed = watchService.removeByQqOpenId(ctx.groupId(), mentionedOpenId);
            return removedMessage(removed);
        }

        return taskCoordinator.queueApiRequest(ctx, "Delete Score Watch", () -> {
            WatchTarget target = resolveTarget(ctx.groupId(), targetArgument);
            return removedMessage(watchService.remove(ctx.groupId(), target.userId())).initialMessage();
        });
    }

    private RouteDecision handleList(Context ctx) {
        if (ctx.argumentCount() != 1) {
            return usage();
        }
        List<WatchView> watches = watchService.list(ctx.groupId());
        if (watches.isEmpty()) {
            return RouteDecision.sync(PendingMessage.ofString("当前群聊没有监视任务。"));
        }

        StringBuilder content = new StringBuilder("当前群聊的监视任务：\n");
        for (WatchView watch : watches) {
            content.append("> ")
                    .append(displayTarget(watch.target()))
                    .append(" - 剩余 ")
                    .append(formatRemaining(watch.remaining()))
                    .append('\n');
        }
        return RouteDecision.sync(PendingMessage.ofMarkdownRaw(content.toString().trim()));
    }

    private WatchTarget resolveTarget(String groupId, String argument) {
        String mentionedOpenId = resolver.extractMentionedUserId(argument);
        if (mentionedOpenId != null) {
            if (!UserDataStore.isGroupMember(groupId, mentionedOpenId)) {
                throw new ResolutionException("指定的用户不在当前群聊中。");
            }
            Long userId = UserDataStore.findBoundUid(mentionedOpenId);
            if (userId == null) {
                throw new ResolutionException("被@的用户还没有绑定玩家ID，请先让对方使用 /bind。");
            }
            User user = findUserById(userId);
            UserDataStore.storeUserInfo(user.getId(), user.getUsername());
            return new WatchTarget(user.getId(), user.getUsername(), mentionedOpenId);
        }

        Long explicitUserId = resolver.parsePositiveLong(argument);
        User user = explicitUserId == null
                ? APIHelper.lookupUser(argument).getContent()
                : findUserById(explicitUserId);
        String qqOpenId = UserDataStore.findGroupOpenIdByUid(groupId, user.getId())
                .orElseThrow(() -> new ResolutionException("指定的玩家不在当前群聊中，或尚未在本群完成绑定。"));
        UserDataStore.storeUserInfo(user.getId(), user.getUsername());
        return new WatchTarget(user.getId(), user.getUsername(), qqOpenId);
    }

    private static User findUserById(long userId) {
        return APIHelper.getUsers(List.of(userId)).stream()
                .filter(user -> user.getId() == userId)
                .findFirst()
                .orElseThrow(() -> new ResolutionException("未找到指定的玩家。"));
    }

    private static RouteDecision removedMessage(WatchView removed) {
        return RouteDecision.sync(PendingMessage.ofMarkdownRaw(
                removed == null
                        ? "当前群聊中没有该用户的监视任务。"
                        : displayTarget(removed.target()) + " 的监视已移除。"
        ));
    }

    private static Integer parseDurationMinutes(String value) {
        if (value == null || !value.matches("\\d+")) {
            return null;
        }
        try {
            int minutes = Integer.parseInt(value);
            return minutes >= 1 && minutes <= MAX_DURATION_MINUTES ? minutes : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String displayTarget(WatchTarget target) {
        return target.username() + "(<qqbot-at-user id=\"" + target.qqOpenId() + "\" />)";
    }

    private static String formatRemaining(Duration duration) {
        long totalSeconds = Math.max(1, (duration.toMillis() + 999) / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + "秒";
        }
        if (seconds == 0) {
            return minutes + "分钟";
        }
        return minutes + "分" + seconds + "秒";
    }

    private static RouteDecision usage() {
        return RouteDecision.sync(PendingMessage.ofString(USAGE));
    }
}
