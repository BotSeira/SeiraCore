package xyz.zcraft.seira.command;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.TargetResolution;
import xyz.zcraft.seira.command.parse.UserRefResolution;
import xyz.zcraft.seira.data.UserRef;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class TargetHistory {
    public enum Type { BEATMAPSET, BEATMAP, SCORE }

    // 每个调用者只保存三个 ID。成绩 ID 使用字符串以兼容 loc... 本地成绩。
    private static final class Ids {
        Long beatmapsetId;
        Long beatmapId;
        String scoreId;

        Ids() {}

        Ids(Ids previous) {
            if (previous != null) {
                beatmapsetId = previous.beatmapsetId;
                beatmapId = previous.beatmapId;
                scoreId = previous.scoreId;
            }
        }
    }

    private final ConcurrentMap<String, Ids> users = new ConcurrentHashMap<>();
    private final Resolver resolver;
    private final Function<String, String> accessToken;

    public TargetHistory(Resolver resolver, Function<String, String> accessToken) {
        this.resolver = resolver;
        this.accessToken = accessToken;
    }

    /** 显式记忆一个新目标，清除旧目标的关联 ID。 */
    public void remember(Context ctx, long id, Type type) {
        remember(ctx, Long.toString(id), type);
    }

    public void remember(Context ctx, String id, Type type) {
        Ids ids = new Ids();
        switch (type) {
            case BEATMAPSET -> ids.beatmapsetId = Long.parseLong(id);
            case BEATMAP -> ids.beatmapId = Long.parseLong(id);
            case SCORE -> ids.scoreId = id;
        }
        users.put(ctx.senderUserId(), ids);
    }

    /** 只取指定类型已经记住的 ID，不进行查找。 */
    public ShortcutTarget get(Context ctx, Type type) {
        Ids ids = users.get(ctx.senderUserId());
        if (ids == null) return null;
        String id = switch (type) {
            case BEATMAPSET -> ids.beatmapsetId == null ? null : ids.beatmapsetId.toString();
            case BEATMAP -> ids.beatmapId == null ? null : ids.beatmapId.toString();
            case SCORE -> ids.scoreId;
        };
        if (id == null) return null;
        if (type == Type.SCORE && isLocalId(id)) return ShortcutTarget.localScore(id);
        return new ShortcutTarget(Long.parseLong(id), null, null, null, null);
    }

    public ShortcutTarget resolveAndGet(Context ctx, Type type) {
        TargetResolution args = ctx.argumentCount() == 0 ? new TargetResolution(null, 0)
                : resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
        if (ctx.argumentCount() != args.consumedArgs()) {
            throw new ResolutionException("目标后还有未处理的参数喵");
        }
        return resolveAndGet(ctx, type, args);
    }

    public ShortcutTarget resolveAndGet(Context ctx, Type type, TargetResolution args) {
        return resolveAndGet(ctx, type, args, List.of());
    }

    /** 在请求工作线程中调用；查找成功后才保存三个 ID，失败时保留原来的记忆。 */
    public ShortcutTarget resolveAndGet(Context ctx, Type type, TargetResolution args, List<String> filters) {
        ShortcutTarget target = args.target();
        if (target != null && target.isError()) throw new ResolutionException(target.errorMessage());

        Ids resolved = users.compute(ctx.senderUserId(), (_, previous) -> {
            // 省略目标时沿用三个 ID；显式输入目标时从空记忆开始。
            Ids ids = target == null ? new Ids(previous) : new Ids();
            UserRef player = args.userOverride() != null ? args.userOverride()
                    : target == null ? null : target.userRef();
            boolean selectedPlayerScore = false;

            if (target != null) {
                if (target.isLocalScore()) {
                    ids.scoreId = target.localScoreId();
                } else if (!target.isMacro()) {
                    switch (type) {
                        case BEATMAPSET -> ids.beatmapsetId = target.explicitId();
                        case BEATMAP -> ids.beatmapId = target.explicitId();
                        case SCORE -> ids.scoreId = target.explicitId().toString();
                    }
                } else {
                    switch (target.macroType()) {
                        case "m" -> ids.beatmapId = target.explicitId();
                        case "s" -> ids.scoreId = target.explicitId().toString();
                        case "ms" -> {
                            ids.beatmapsetId = target.explicitId();
                            if (type != Type.BEATMAPSET) {
                                if (target.macroIndex() == null) throw new ResolutionException("请指定指令目标谱面喵");
                                if (type == Type.BEATMAP) {
                                    ids.beatmapId = APIHelper.lookupBeatmap(target, accessToken.apply(ctx.senderUserId()));
                                } else {
                                    player = requirePlayer(ctx, player);
                                    ids.scoreId = APIHelper.lookupScoreId(new ShortcutTarget(
                                            target.explicitId(), player, "ms", target.macroIndex(), null), filters);
                                    selectedPlayerScore = true;
                                }
                            }
                        }
                        case "rs", "rp", "bp" -> {
                            // 先把列表位置固定为实际成绩 ID，后续指令不再重新查询列表。
                            player = requirePlayer(ctx, player);
                            ids.scoreId = APIHelper.lookupScoreId(new ShortcutTarget(
                                    null, player, target.macroType(), target.macroIndex(), null), filters);
                            selectedPlayerScore = true;
                        }
                        case "mp" -> {
                            if (type == Type.BEATMAPSET) {
                                ids.beatmapsetId = APIHelper.lookupBeatmapset(target, accessToken.apply(ctx.senderUserId()));
                            } else {
                                ids.beatmapId = APIHelper.lookupBeatmap(target, accessToken.apply(ctx.senderUserId()));
                            }
                        }
                        default -> throw new ResolutionException("未知的快捷查询");
                    }
                }
            }

            // /s @用户：取记忆成绩所在的谱面，再查指定用户的成绩。
            // /s rs2 @用户 已经选好了该用户的 rs2，不再改查谱面最佳成绩。
            if (type == Type.SCORE && args.userOverride() != null && ids.scoreId != null && !selectedPlayerScore) {
                if (ids.beatmapId == null) {
                    ids.beatmapId = APIHelper.lookupBeatmap(scoreTarget(ids.scoreId), accessToken.apply(ctx.senderUserId()));
                }
                ids.scoreId = null;
            }

            switch (type) {
                case BEATMAP -> {
                    if (ids.beatmapId == null) {
                        if (ids.scoreId == null) throw new ResolutionException("请指定指令目标谱面喵");
                        ids.beatmapId = APIHelper.lookupBeatmap(scoreTarget(ids.scoreId), accessToken.apply(ctx.senderUserId()));
                    }
                }
                case BEATMAPSET -> {
                    if (ids.beatmapsetId == null) {
                        if (ids.beatmapId == null && ids.scoreId != null) {
                            ids.beatmapId = APIHelper.lookupBeatmap(scoreTarget(ids.scoreId), accessToken.apply(ctx.senderUserId()));
                        }
                        if (ids.beatmapId == null) throw new ResolutionException("请指定指令目标喵");
                        ids.beatmapsetId = APIHelper.lookupBeatmapset(new ShortcutTarget(
                                ids.beatmapId, null, "m", null, null), accessToken.apply(ctx.senderUserId()));
                    }
                }
                case SCORE -> {
                    if (ids.scoreId == null) {
                        if (ids.beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
                        player = requirePlayer(ctx, player);
                        ids.scoreId = APIHelper.lookupScoreId(new ShortcutTarget(
                                ids.beatmapId, player, "m", null, null), filters);
                    }
                }
            }
            return ids;
        });

        // 直接返回本次结果，避免并发指令写入后读到别的目标。
        return switch (type) {
            case BEATMAPSET -> new ShortcutTarget(resolved.beatmapsetId, null, null, null, null);
            case BEATMAP -> new ShortcutTarget(resolved.beatmapId, null, null, null, null);
            case SCORE -> isLocalId(resolved.scoreId) ? ShortcutTarget.localScore(resolved.scoreId)
                    : new ShortcutTarget(Long.parseLong(resolved.scoreId), null, null, null, null);
        };
    }

    private UserRef requirePlayer(Context ctx, UserRef player) {
        if (player != null) return player;
        Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) throw new ResolutionException("请先绑定 osu! 账号，再查找记忆谱面上的成绩喵");
        return new UserRef.ByUid(uid);
    }

    private static boolean isLocalId(String id) {
        return !id.chars().allMatch(Character::isDigit);
    }

    // 仅在跨类型查找时标注这是成绩 ID，避免 APIHelper 将其当成谱面 ID。
    private static ShortcutTarget scoreTarget(String id) {
        return isLocalId(id) ? ShortcutTarget.localScore(id)
                : new ShortcutTarget(Long.parseLong(id), null, "s", null, null);
    }

    /** 同屏回放需要保留本地成绩本身，以便把它加入回放列表。 */
    public boolean isLocalScore(Context ctx, TargetResolution args) {
        if (args.target() != null) return args.target().isLocalScore();
        Ids ids = users.get(ctx.senderUserId());
        return ids != null && ids.scoreId != null && isLocalId(ids.scoreId);
    }

    public TargetResolution parseArguments(Context ctx, String usage, int maxOptions) {
        return parseArguments(ctx, usage, maxOptions, _ -> false);
    }

    /** optional 判断首个参数是否是省略目标后的选项；返回的 consumedArgs 标记选项起点。 */
    public TargetResolution parseArguments(Context ctx, String usage, int maxOptions, Predicate<String> optional) {
        try {
            TargetResolution args;
            if (ctx.argumentCount() == 0 || optional.test(ctx.argument(0))) {
                if (!users.containsKey(ctx.senderUserId())) throw new ResolutionException(usage);
                args = new TargetResolution(null, 0);
            } else {
                args = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
                if (args.target().isError()) throw new ResolutionException(args.target().errorMessage());
            }
            if (ctx.argumentCount() - args.consumedArgs() > maxOptions) throw new ResolutionException(usage);
            return args;
        } catch (ResolutionException e) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + e.getMessage()));
            return null;
        }
    }

    public TargetResolution parseScoreArguments(Context ctx, String usage) {
        try {
            if (ctx.argumentCount() > 2) throw new ResolutionException(usage);
            if (ctx.argumentCount() == 1 && resolver.looksLikeMention(ctx.argument(0))) {
                UserRef player = parsePlayer(ctx.argument(0), usage);
                if (!users.containsKey(ctx.senderUserId())) throw new ResolutionException(usage);
                return new TargetResolution(null, 1, player);
            }
            TargetResolution args = parseArguments(ctx, usage, 1);
            if (args == null || args.nextArgument(ctx) == null) return args;
            return new TargetResolution(args.target(), args.consumedArgs() + 1, parsePlayer(args.nextArgument(ctx), usage));
        } catch (ResolutionException e) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + e.getMessage()));
            return null;
        }
    }

    private UserRef parsePlayer(String argument, String usage) {
        UserRefResolution result = resolver.resolveUserRefArgument(argument);
        if (result.errorMessage() != null) throw new ResolutionException(result.errorMessage());
        if (result.userRef() == null) throw new ResolutionException(usage);
        return result.userRef();
    }
}
