package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.bot.data.Button;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.challenge.ChallengeMessages;
import xyz.zcraft.seira.challenge.ChallengeService;
import xyz.zcraft.seira.challenge.ChallengeSetupMessages;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ChallengeCommandHandler {
    public static final String USAGE = """
            > 用法：/gch configure → 通过按钮设置 → /gch start
            > /gch set <set/map/random/hours/difficulty/required/banned/stars/skill> <值>
            > 快速开始：/gch start <谱面集ID> [小时数，默认24，最多168]
            > /gch join | status | lb [页数] | submit <成绩ID> | end | rules
            """;
    private final ChallengeService service;
    private final Function<String, Long> binding;
    private final Predicate<String> admins;

    public ChallengeCommandHandler(ChallengeService service, Function<String, Long> binding,
                                   Predicate<String> admins) {
        this.service = service;
        this.binding = binding;
        this.admins = admins;
    }

    private static long positive(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("ID、小时数和页数必须是正整数。");
    }

    private static void usage(Context ctx) {
        ctx.sendReply(at(ctx) + USAGE);
    }

    public void handleChallenge(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(at(ctx) + "/gch 仅支持群聊使用。");
            return;
        }
        try {
            String action = ctx.argumentCount() == 0 ? "status" : ctx.argument(0).toLowerCase(Locale.ROOT);
            switch (action) {
                case "configure", "config" -> {
                    boolean reset = ctx.argumentCount() == 2 && ctx.argument(1).equalsIgnoreCase("reset");
                    if (ctx.argumentCount() > 1 && !reset) {
                        usage(ctx);
                        return;
                    }
                    var draft = service.configure(ctx.groupId(), ctx.senderUserId(), reset, admins.test(ctx.senderUserId()));
                    setup(ctx, draft, "main");
                }
                case "set", "edit" -> {
                    boolean button = action.equals("edit");
                    int offset = button ? 3 : 1;
                    if (ctx.argumentCount() < offset + 1 || ctx.argumentCount() > offset + 2) {
                        usage(ctx);
                        return;
                    }
                    String key = ctx.argument(offset);
                    String value = ctx.argumentCount() == offset + 2 ? ctx.argument(offset + 1) : "";
                    if (!key.equals("random") && !key.equals("reroll") && value.isEmpty()) {
                        usage(ctx);
                        return;
                    }
                    if (key.equals("random") || key.equals("reroll") || key.equals("map") || key.equals("set"))
                        ctx.sendReply(at(ctx) + "正在获取谱面，请稍候……");
                    var draft = service.edit(ctx.groupId(), ctx.senderUserId(), admins.test(ctx.senderUserId()),
                            button ? ctx.argument(1) : null, button ? Long.parseLong(ctx.argument(2)) : null, key, value);
                    setup(ctx, draft, key.equals("required") || key.equals("banned") ? key : "main");
                }
                case "menu" -> {
                    if (ctx.argumentCount() != 4) {
                        usage(ctx);
                        return;
                    }
                    var draft = service.getDraft(ctx.groupId());
                    if (draft == null || !draft.id().equals(ctx.argument(1)) || draft.revision() != Long.parseLong(ctx.argument(2)))
                        throw new IllegalArgumentException("配置按钮已过期，请重新执行 /gch configure。");
                    String panel = ctx.argument(3);
                    if (!java.util.Set.of("main", "mods", "required", "banned").contains(panel)) {
                        usage(ctx);
                        return;
                    }
                    setup(ctx, draft, panel);
                }
                case "start" -> {
                    if (ctx.argumentCount() == 1 || ctx.argumentCount() == 3 && !ctx.argument(1).matches("[0-9]+")) {
                        var round = service.startConfigured(ctx.groupId(), ctx.senderUserId(), admins.test(ctx.senderUserId()),
                                ctx.argumentCount() == 3 ? ctx.argument(1) : null,
                                ctx.argumentCount() == 3 ? Long.parseLong(ctx.argument(2)) : null);
                        ctx.sendReply(ChallengeMessages.status(round, service.now()));
                        return;
                    }
                    if (ctx.argumentCount() < 2 || ctx.argumentCount() > 3) {
                        usage(ctx);
                        return;
                    }
                    long set = positive(ctx.argument(1));
                    long hours = ctx.argumentCount() == 3 ? positive(ctx.argument(2)) : ChallengeService.DEFAULT_HOURS;
                    if (hours > ChallengeService.MAX_HOURS)
                        throw new IllegalArgumentException("挑战时长为 1–168 小时。");
                    ctx.sendReply(ChallengeMessages.status(service.start(ctx.groupId(), ctx.senderUserId(), set, (int) hours,
                            admins.test(ctx.senderUserId())), service.now()));
                }
                case "join" -> {
                    if (ctx.argumentCount() != 1) {
                        usage(ctx);
                        return;
                    }
                    ctx.sendReply(at(ctx) + "正在获取参赛账号并估计水平，请稍候……");
                    var member = service.join(ctx.groupId(), ctx.senderUserId(), boundUid(ctx));
                    ctx.sendReply(at(ctx) + (member.skillSamples() == 0 ? "报名成功喵。" : String.format(Locale.ROOT,
                            """
                                    报名成功：%s
                                    > 小星觉得你的水平能打 %.2f★
                                    > 选图每高 1★ ×1.05；每低 1★ ×0.80
                                    > 现在可选择本次谱面集的任意合格难度游玩，成绩会自动收集喵""",
                            ChallengeMessages.clean(member.username()), member.skillStars())));
                }
                case "submit" -> {
                    if (ctx.argumentCount() != 2) {
                        usage(ctx);
                        return;
                    }
                    var round = service.submit(ctx.groupId(), ctx.senderUserId(), boundUid(ctx), positive(ctx.argument(1)));
                    ctx.sendReply(at(ctx) + "成绩已检查，保留你的最高挑战分。\n" + ChallengeMessages.leaderboard(round, 1));
                }
                case "end" -> {
                    if (ctx.argumentCount() != 1) {
                        usage(ctx);
                        return;
                    }
                    service.end(ctx.groupId(), ctx.senderUserId(), admins.test(ctx.senderUserId()));
                    ctx.sendReply(at(ctx) + "挑战已结束，已按收录的成绩结算。可用 /gch lb 查看结果。");
                }
                case "lb" -> {
                    if (ctx.argumentCount() > 2) {
                        usage(ctx);
                        return;
                    }
                    long page = ctx.argumentCount() == 2 ? positive(ctx.argument(1)) : 1;
                    if (page > 10) throw new IllegalArgumentException("排行榜最多 10 页。");
                    var round = service.get(ctx.groupId());
                    if (round == null) {
                        usage(ctx);
                        return;
                    }
                    ctx.sendReply(at(ctx) + ChallengeMessages.leaderboard(round, (int) page));
                }
                case "status" -> {
                    if (ctx.argumentCount() > 1) {
                        usage(ctx);
                        return;
                    }
                    var round = service.get(ctx.groupId());
                    if (round == null) {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "本群暂无挑战。\n" + USAGE,
                                Button.keyboard(Button.row(Button.command(1, "创建一局游戏", "/gch configure")))
                        ));
                    } else {
                        ctx.sendReply(ChallengeMessages.status(round, service.now()));
                    }
                }
                case "rules" -> {
                    if (ctx.argumentCount() != 1) {
                        usage(ctx);
                        return;
                    }
                    ctx.sendReply(at(ctx) + ChallengeMessages.RULES);
                }
                default -> usage(ctx);
            }
        } catch (IllegalArgumentException e) {
            ctx.sendReply(at(ctx) + e.getMessage());
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger(ChallengeCommandHandler.class).error("Failed to handle challenge command", e);
            ctx.sendReply(at(ctx) + TaskCoordinator.resolveErrorMessage(e));
        }
    }

    private void setup(Context ctx, xyz.zcraft.seira.challenge.ChallengeModels.Draft draft, String panel) {
        var round = service.get(ctx.groupId());
        ctx.sendReply(ChallengeSetupMessages.render(draft, admins.test(ctx.senderUserId()) ? ctx.senderUserId() : draft.owner(),
                panel, round == null || round.finished()));
    }

    private long boundUid(Context ctx) {
        Long uid = binding.apply(ctx.senderUserId());
        if (uid == null) throw new IllegalArgumentException("请先执行 /bind 绑定自己的 osu! 账号。");
        return uid;
    }
}
