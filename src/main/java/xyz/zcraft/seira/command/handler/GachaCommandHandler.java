package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.api.data.gacha.GachaCardOwn;
import xyz.zcraft.seira.api.data.gacha.GachaPlayer;
import xyz.zcraft.seira.api.data.gacha.OsuGacha;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.TargetResolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.db.UserDataStore;

import java.util.*;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public class GachaCommandHandler {
    private static final Logger LOG = LogManager.getLogger(GachaCommandHandler.class);
    private final MessageSender messageSender;
    private final TargetResolver targets;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;

    public GachaCommandHandler(
            MessageSender messageSender,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Resolver resolver,
            TargetResolver targets,
            TargetHistory history,
            Predicate<String> adminAuthorizer
    ) {
        this.messageSender = messageSender;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.resolver = resolver;
        this.targets = targets;
        this.history = history;
        this.adminAuthorizer = adminAuthorizer;
    }

    public void handleGacha(Context ctx) {
        if (ctx.argumentCount() == 0
                || (ctx.argumentCount() == 1 && resolver.looksLikeMention(ctx.argument(0)))) {
            handleUserProfile(ctx);
            return;
        } else if (ctx.argumentCount() == 1
                || (ctx.argumentCount() == 2 && resolver.looksLikeMention(ctx.argument(1)))) {
            if (ctx.argument(0).equalsIgnoreCase("cards")) {
                handlePlayerCardOwners(ctx);
                return;
            }
        }

        ctx.sendReply("用法: /gacha [cards] [用户]");
    }

    private void handleUserProfile(Context ctx) {
        String username;

        if (ctx.argumentCount() == 0) {
            final long l = resolver.requireBind(ctx);
            username = UserDataStore.findUsername(l).orElseThrow(() -> new RuntimeException("未找到用户名"));
        } else {
            username = resolver.player(ctx.argument(0), null);
        }

        final byte[] gachaProfile = AsteroidApi.getGachaProfile(username);

        ctx.sendReply(PendingMessage.ofImageBase64(Base64.getEncoder().encodeToString(gachaProfile)));
    }

    private void handlePlayerCardOwners(Context ctx) {
        long uid;

        if (ctx.argumentCount() <= 1) {
            uid = resolver.requireBind(ctx);
        } else {
            uid = OstellaApi.resolveUid(resolver.player(ctx.argument(1), null));
        }

        StringBuilder sb = new StringBuilder(at(ctx));

        final UserExtended userRaw = OstellaApi.getUserRaw(uid);

        final List<GachaPlayer> players = AsteroidApi.searchGachaPlayer(userRaw.getUsername());

        final Optional<GachaPlayer> playerCardOptional = players.stream()
                .filter(e -> e.username().equalsIgnoreCase(userRaw.getUsername()))
                .findFirst();

        if (playerCardOptional.isEmpty()) {
            ctx.sendReply(at(ctx) + "`%s` 还没有自己的卡片喵。".formatted(userRaw.getUsername()));
            return;
        }

        final GachaPlayer gachaPlayer = playerCardOptional.get();

        final List<GachaCardOwn> gachaCardOwners = AsteroidApi.getGachaCardOwners(uid);
        final Map<String, Long> boundUsers = UserDataStore.findBoundUsersByGroup(ctx.groupId());

        final long sum = gachaCardOwners.stream().mapToLong(GachaCardOwn::count).sum();

        sb.append("""
                `%s` 的卡片信息：
                > 职业：`%s`，稀有度：`%.2f`（%s）
                > 已被抽出 `%d` 张，现存 `%d` 张"""
                .formatted(
                        userRaw.getUsername(),
                        OsuGacha.getClassName(gachaPlayer.cls()),
                        gachaPlayer.popularity() / 100.0,
                        OsuGacha.getRarityName(gachaPlayer.currentRarity()),
                        gachaPlayer.mintedCount(),
                        sum
                )
        );

        sb.append("\n卡片共 `%d` 位持有者：\n".formatted(gachaCardOwners.size()));

        if (gachaCardOwners.isEmpty()) {
            sb.append("- (暂无持有者)");
        } else {
            final Set<String> groupUsernames = new HashSet<>();

            for (Long value : boundUsers.values()) {
                UserDataStore.findUsername(value)
                        .map(name -> name.toLowerCase(Locale.ROOT))
                        .ifPresent(groupUsernames::add);
            }

            final Comparator<GachaCardOwn> comparator = Comparator
                    .comparing((GachaCardOwn e) -> !groupUsernames.contains(e.username().toLowerCase(Locale.ROOT)))
                    .thenComparing(Comparator.comparingLong(GachaCardOwn::count).reversed())
                    .thenComparingLong(GachaCardOwn::lowest);

            final List<GachaCardOwn> sorted = gachaCardOwners.stream()
                    .sorted(comparator)
                    .toList();

            final long groupOwnerCount = sorted.stream()
                    .filter(e -> groupUsernames.contains(e.username().toLowerCase(Locale.ROOT)))
                    .count();

            final int showCount = (int) Math.clamp(10, groupOwnerCount, sorted.size());

            for (int i = 0; i < showCount; i++) {
                GachaCardOwn owner = sorted.get(i);
                final boolean inGroup = groupUsernames.contains(owner.username().toLowerCase());
                sb.append("- ").append("%s`%s`: `%d`张 (`#%d`)".formatted(inGroup ? "※ " : "", owner.username(), owner.count(), owner.lowest())).append("\n");
            }

            final long collapsedCount = sorted.stream()
                    .skip(showCount)
                    .mapToLong(GachaCardOwn::count)
                    .sum();

            if (showCount < sorted.size()) {
                sb.append("- 还有 %d 个用户的合计 %d 张".formatted(sorted.size() - showCount, collapsedCount));
            }
        }

        ctx.sendReply(sb.toString().trim());
    }
}
