package xyz.zcraft.seira.command.route;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.data.ApiTask;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.handler.*;
import xyz.zcraft.seira.command.iface.CommandMetrics;
import xyz.zcraft.seira.command.parse.CommandParser;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.game.RankGuessGameService;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.util.OsuAuthHelper;
import xyz.zcraft.seira.util.ThreadHelper;
import xyz.zcraft.seira.watch.ScoreWatchService;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Router {
    private static final Logger LOG = LogManager.getLogger(Router.class);

    private final AppConfig config;
    private final TaskCoordinator taskCoordinator;
    private final OsuAuthHelper authHelper;
    private final CommandParser commandParser;
    private final CommandRegistry commandRegistry;
    private final DebugRoutes debugRoutes;
    private final CommandMetrics metrics;
    private final Supplier<RouteDecision> unknownCommand;

    public Router(MessageSender messageSender, AppConfig config, ScoreWatchService watchService, RankGuessGameService rankGuessGameService) {
        this.config = config;
        this.metrics = BotStat::incrementCommands;
        ReplyFactory replyFactory = new ReplyFactory(config);
        Resolver resolver = new Resolver();
        TargetHistory targetHistory = new TargetHistory();
        ReplayResultStore replayResults = new ReplayResultStore();
        this.taskCoordinator = new TaskCoordinator(messageSender, replayResults);
        this.authHelper = new OsuAuthHelper(config.binding());
        BindingCommandHandler bindingCommands = new BindingCommandHandler(config, replyFactory);
        ScoreCommandHandler scoreCommands = new ScoreCommandHandler(
                resolver, targetHistory, taskCoordinator, replyFactory
        );
        BeatmapCommandHandler beatmapCommands = new BeatmapCommandHandler(
                resolver, targetHistory, taskCoordinator, replyFactory, this::getAccessTokenFor
        );
        SocialCommandHandler socialCommands = new SocialCommandHandler(
                resolver, authHelper, taskCoordinator, replyFactory, this::getAccessTokenFor
        );
        ReplayCommandHandler replayCommands = new ReplayCommandHandler(
                resolver,
                targetHistory,
                taskCoordinator,
                replyFactory,
                new VideoRenderRecord(),
                replayResults,
                this::getAccessTokenFor
        );
        GeneralCommandHandler generalCommands = new GeneralCommandHandler(
                messageSender, taskCoordinator, replyFactory, scoreCommands, this::isAdmin, metrics
        );
        WatchCommandHandler watchCommands = new WatchCommandHandler(resolver, taskCoordinator, watchService, this::isAdmin);
        RankGuessCommandHandler rankGuessCommands = new RankGuessCommandHandler(
                taskCoordinator, replyFactory, rankGuessGameService, this::isAdmin
        );
        this.unknownCommand = generalCommands::handleUnknown;
        this.commandParser = new CommandParser(resolver::preProcess);
        this.commandRegistry = createCommandRegistry(
                bindingCommands,
                scoreCommands,
                beatmapCommands,
                socialCommands,
                replayCommands,
                generalCommands,
                watchCommands,
                rankGuessCommands
        );
        this.debugRoutes = new DebugRoutes(
                config,
                messageSender,
                replyFactory,
                taskCoordinator,
                authHelper,
                this::isAdmin,
                unknownCommand
        );
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

            rawContent = rawContent.trim();

            final String selfAt = "<@" + config.qq().selfId() + ">";
            if (rawContent.startsWith(selfAt)) {
                rawContent = rawContent.substring(selfAt.length()).trim();
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

    public RouteDecision route(String rawContent, String senderUserId, String groupId, String messageId) {
        CommandParser.ParseResult result = commandParser.parse(
                rawContent, senderUserId, groupId, messageId
        );

        return switch (result.status()) {
            case IGNORED -> null;
            case EMPTY_COMMAND -> RouteDecision.sync(PendingMessage.ofString("请输入指令。使用/help获取帮助。"));
            case PARSED -> dispatch(result.context());
        };
    }

    private RouteDecision dispatch(Context ctx) {
        metrics.commandReceived();
        if (ctx.command().startsWith("debug.")) {
            return debugRoutes.routeDebug(ctx);
        }
        return commandRegistry.dispatch(ctx, unknownCommand);
    }

    private static CommandRegistry createCommandRegistry(
            BindingCommandHandler bindingCommands,
            ScoreCommandHandler scoreCommands,
            BeatmapCommandHandler beatmapCommands,
            SocialCommandHandler socialCommands,
            ReplayCommandHandler replayCommands,
            GeneralCommandHandler generalCommands,
            WatchCommandHandler watchCommands,
            RankGuessCommandHandler rankGuessCommands
    ) {
        return CommandRegistry.builder()
                .register(bindingCommands::handleBind, "bind")
                .register(bindingCommands::handleUnbind, "unbind")
                .register(bindingCommands::handleClearHistory, "clearhistory")
                .register(scoreCommands::handleBo, "bp", "bo")
                .register(beatmapCommands::handleDaily, "daily")
                .register(socialCommands::handleMp, "mp")
                .register(ctx -> scoreCommands.handleRs(ctx, true), "rs")
                .register(ctx -> scoreCommands.handleRs(ctx, false), "rp")
                .register(beatmapCommands::handleM, "m")
                .register(beatmapCommands::handleAp, "ap")
                .register(beatmapCommands::handleBgp, "bgp")
                .register(ctx -> socialCommands.handleF(ctx, !ctx.inGroup()), "f")
                .register(ctx -> socialCommands.handleF(ctx, true), "fall")
                .register(socialCommands::handleFclear, "fclear")
                .register(beatmapCommands::handleDl, "dl")
                .register(scoreCommands::handleS, "s")
                .register(scoreCommands::handleSa, "sa")
                .register(scoreCommands::handleMa, "ma")
                .register(replayCommands::handleR, "r")
                .register(replayCommands::handleRsc, "rsc")
                .register(beatmapCommands::handleMs, "ms")
                .register(beatmapCommands::handleSms, "sms")
                .register(socialCommands::handleLb, "lb")
                .register(generalCommands::handleStat, "stat")
                .register(generalCommands::handleU, "u")
                .register(generalCommands::handleLuck, "luck")
                .register(replayCommands::handleRstat, "rstat")
                .register(generalCommands::handleInspect, "inspect")
                .register(generalCommands::handleHelp, "help")
                .register(generalCommands::handleFaq, "faq")
                .register(watchCommands::handleWatch, "watch")
                .register(rankGuessCommands::handleRankGuess, "rg")
                .build();
    }

    public Set<String> registeredCommands() {
        return commandRegistry.registeredCommands();
    }

    private String getAccessTokenFor(String openId) {
        return Optional.ofNullable(authHelper.updateTokenAndGet(openId))
                .map(OsuToken::accessToken)
                .orElse(null);
    }

    private boolean isAdmin(String openId) {
        final List<String> adminIds = config.seira().adminIds();
        if (adminIds == null || adminIds.isEmpty()) {
            return false;
        }
        return adminIds.contains(openId);
    }

    public void sendAttachmentUploadMessage(String openId, String msgId, PendingMessage s) {
        taskCoordinator.sendOutboundMessage(openId, msgId, false, s, new AtomicInteger(10));
    }

}
