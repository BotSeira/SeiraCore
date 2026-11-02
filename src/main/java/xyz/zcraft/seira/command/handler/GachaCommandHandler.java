package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.api.data.gacha.GachaCardOwn;
import xyz.zcraft.seira.api.data.gacha.GachaPlayer;
import xyz.zcraft.seira.api.data.gacha.Guide;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;
import static xyz.zcraft.seira.command.reply.ReplyFactory.cmd;

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
        } else if (ctx.argumentCount() >= 1 && ctx.argumentCount() <= 3
                && "cards".equalsIgnoreCase(ctx.argument(0))) {
            handlePlayerCardOwners(ctx);
            return;
        } else if (ctx.argumentCount() >= 1 && ctx.argumentCount() <= 3
                && "market".equalsIgnoreCase(ctx.argument(0))) {
            handleMarket(ctx);
            return;
        }

        ctx.sendReply("用法: /gacha [cards|market] [目标]");
    }

    public static final Set<String> ITEMS = Set.of(
            "replay_1", "replay_2", "replay_3",
            "boss_trophy", "platinum_trophy", "diamond_trophy", "emerald_trophy",
            "insignia_tank_1", "insignia_tank_2", "insignia_tank_3", "insignia_tank_4",
            "insignia_dps_1", "insignia_dps_2", "insignia_dps_3", "insignia_dps_4",
            "insignia_support_1", "insignia_support_2", "insignia_support_3", "insignia_support_4",
            "insignia_cc_1", "insignia_cc_2", "insignia_cc_3", "insignia_cc_4",
            "fuel", "mod_fragment", "banner_key"
    );

    private void handleMarket(Context ctx) {
        if (ctx.argumentCount() == 1) {
            String sb = at(ctx) + "选择要查看的物品：\n" +
                    """
                            > %s %s %s
                            > 回放: %s %s %s
                            > 奖杯: %s %s %s %s
                            > 职业徽章:
                            > - 坦克: %s %s %s %s
                            > - 输出: %s %s %s %s
                            > - 支援: %s %s %s %s
                            > - 控制: %s %s %s %s
                            """
                            .formatted(
                                    cmd("/gacha market fuel", "[  燃料  ]"),
                                    cmd("/gacha market mod_fragment", "[模组碎片]"),
                                    cmd("/gacha market banner_key", "[卡池钥匙]"),

                                    cmd("/gacha market replay_1", "[  铜  ]"),
                                    cmd("/gacha market replay_2", "[  银  ]"),
                                    cmd("/gacha market replay_3", "[  金  ]"),

                                    cmd("/gacha market boss_trophy", "[  金  ]"),
                                    cmd("/gacha market platinum_trophy", "[ 白金 ]"),
                                    cmd("/gacha market diamond_trophy", "[ 钻石 ]"),
                                    cmd("/gacha market emerald_trophy", "[ 翡翠 ]"),

                                    cmd("/gacha market insignia_tank_1", "[  I  ]"),
                                    cmd("/gacha market insignia_tank_2", "[  II  ]"),
                                    cmd("/gacha market insignia_tank_3", "[ III ]"),
                                    cmd("/gacha market insignia_tank_4", "[  IV  ]"),

                                    cmd("/gacha market insignia_dps_1", "[  I  ]"),
                                    cmd("/gacha market insignia_dps_2", "[  II  ]"),
                                    cmd("/gacha market insignia_dps_3", "[ III ]"),
                                    cmd("/gacha market insignia_dps_4", "[  IV  ]"),

                                    cmd("/gacha market insignia_support_1", "[  I  ]"),
                                    cmd("/gacha market insignia_support_2", "[  II  ]"),
                                    cmd("/gacha market insignia_support_3", "[ III ]"),
                                    cmd("/gacha market insignia_support_4", "[  IV  ]"),

                                    cmd("/gacha market insignia_cc_1", "[  I  ]"),
                                    cmd("/gacha market insignia_cc_2", "[  II  ]"),
                                    cmd("/gacha market insignia_cc_3", "[ III ]"),
                                    cmd("/gacha market insignia_cc_4", "[  IV  ]")
                            );

            ctx.sendReply(sb.trim());
            return;
        }

        if (ctx.argument(1).startsWith("@") || resolver.looksLikeMention(ctx.argument(1))) {
            handleMarketPlayerCard(ctx);
        } else {
            handleMarketItemTrend(ctx);
        }
    }

    private static final Map<String, Set<String>> ITEM_ALASES = Map.of(
            "fuel", Set.of("燃料"),
            "mod_fragment", Set.of("Mod碎片", "模组碎片"),
            "banner_key", Set.of("卡池钥匙"),
            "replay_1", Set.of("铜回放"),
            "replay_2", Set.of("银回放"),
            "replay_3", Set.of("金回放"),
            "boss_trophy", Set.of("金奖杯"),
            "platinum_trophy", Set.of("白金奖杯"),
            "diamond_trophy", Set.of("钻石奖杯"),
            "emerald_trophy", Set.of("翡翠奖杯")
    );

    private void handleMarketPlayerCard(Context ctx) {
        final String player = resolver.player(ctx.argument(1), null);
        final long l = OstellaApi.resolveUid(player);
        final UserExtended userRaw = OstellaApi.getUserRaw(l);

        final Guide guide = AsteroidApi.getGachaPlayerMarketGuide(player);
        AsteroidApi.searchGachaPlayer(player);

        if (guide == null || guide.listed() == 0) {
            ctx.sendReply(at(ctx) + "目前市场上没有 `%s` 的卡片喵。".formatted(userRaw.getUsername()));
            return;
        }

        ctx.sendReply(at(ctx) + """
                `%s` 的卡片在售 `%d` 个:
                > 最低 / 中位 / 最高:
                > %d / %d / %d
                > 近 %d 天售出: %d
                """
                .formatted(
                        userRaw.getUsername(),
                        guide.listed(),
                        guide.lowest(), guide.median(), guide.highest(),
                        guide.days(), guide.sales()
                ).trim()
        );
    }

    private void handleMarketItemTrend(Context ctx) {
        String target = ctx.argument(1).toLowerCase(Locale.ROOT);
        String scale = ctx.argumentCount() == 3 ? ctx.argument(2).toLowerCase(Locale.ROOT) : "8h";

        for (var entry : ITEM_ALASES.entrySet()) {
            if (entry.getValue().contains(target)) {
                target = entry.getKey();
                break;
            }
        }

        if (!ITEMS.contains(target)) {
            ctx.sendReply(at(ctx) + "未知的物品:" + target + "\n> 注：目前仅支持查询物品和玩家卡片喵。");
            return;
        }

        final Matcher matcher = SCALE.matcher(scale);

        if (!matcher.matches()) {
            ctx.sendReply(at(ctx) + "时间格式不正确喵。");
            return;
        }

        if (matcher.group(2).equalsIgnoreCase("m")) {
            scale = matcher.group(1) + "min";
        }

        final byte[] result = AsteroidApi.getGachaMarketTrend(target, scale);

        ctx.sendReply(PendingMessage.ofImageBase64(Base64.getEncoder().encodeToString(result)));
    }

    private static final Pattern SCALE = Pattern.compile("([1-9][0-9]{0,3})(m|min|h|d|mo)");

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
