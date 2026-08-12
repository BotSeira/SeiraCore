package xyz.zcraft.seira.command.route;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.binding.BindingService;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.handler.*;
import xyz.zcraft.seira.command.parse.CommandParser;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.discord.DiscordBridgeService;
import xyz.zcraft.seira.rankguess.RankGuessGameService;
import xyz.zcraft.seira.security.AdminRegistry;
import xyz.zcraft.seira.util.OsuAuthHelper;
import xyz.zcraft.seira.watch.ScoreWatchService;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Router {
    private static final Logger LOG = LogManager.getLogger(Router.class);

    private final Supplier<AppConfig> configSupplier;
    private final TaskCoordinator taskCoordinator;
    private final OsuAuthHelper authHelper;
    private final CommandParser commandParser;
    private final CommandRegistry commandRegistry;
    private final DebugRoutes debugRoutes;
    private final Runnable commandMetric;
    private final CommandHandler unknownCommand;
    private final Executor commandExecutor;

    public Router(
            MessageSender messageSender,
            Supplier<AppConfig> configSupplier,
            AdminRegistry admins,
            BindingService bindingService,
            ScoreWatchService watchService,
            DiscordBridgeService discordBridgeService,
            RankGuessGameService rankGuessGameService,
            Executor commandExecutor,
            Runnable commandMetric
    ) {
        this.configSupplier = java.util.Objects.requireNonNull(configSupplier);
        this.commandExecutor = commandExecutor;
        this.commandMetric = java.util.Objects.requireNonNull(commandMetric);
        AppConfig startupConfig = configSupplier.get();
        ReplyFactory replyFactory = new ReplyFactory(configSupplier);
        Resolver resolver = new Resolver();
        TargetHistory targetHistory = new TargetHistory();
        ReplayResultStore replayResults = new ReplayResultStore();
        this.taskCoordinator = new TaskCoordinator(messageSender, replayResults);
        this.authHelper = new OsuAuthHelper(startupConfig.binding());
        BindingCommandHandler bindingCommands = new BindingCommandHandler(startupConfig, replyFactory, bindingService);
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
                messageSender, taskCoordinator, replyFactory, scoreCommands, admins::isAdmin, commandMetric
        );
        WatchCommandHandler watchCommands = new WatchCommandHandler(resolver, taskCoordinator, watchService, admins::isAdmin);
        SpecificScoreWatchCommandHandler specificScoreWatchCommands =
                new SpecificScoreWatchCommandHandler(taskCoordinator, watchService);
        DcsCommandHandler dcsCommands = new DcsCommandHandler(discordBridgeService);
        RankGuessCommandHandler rankGuessCommands = new RankGuessCommandHandler(
                taskCoordinator, replyFactory, rankGuessGameService, admins::isAdmin
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
                specificScoreWatchCommands,
                dcsCommands,
                rankGuessCommands
        );
        this.debugRoutes = new DebugRoutes(
                configSupplier,
                messageSender,
                replyFactory,
                taskCoordinator,
                authHelper,
                admins::isAdmin,
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

            rawContent = rawContent == null ? "" : rawContent.trim();

            AppConfig config = configSupplier.get();
            final String selfAt = "<@" + config.qq().selfId() + ">";
            if (rawContent.startsWith(selfAt)) {
                rawContent = rawContent.substring(selfAt.length()).trim();
            }

            CommandParser.ParseResult parseResult = commandParser.parse(
                    rawContent, userId, groupId, messageId
            );
            if (parseResult.status() == CommandParser.ParseResult.Status.IGNORED) {
                return;
            }

            CommandReplyChannel replies = taskCoordinator.openReplyChannel(
                    targetId,
                    messageId,
                    groupMessage,
                    config.seira().queueMessageInGroup()
            );
            if (parseResult.status() == CommandParser.ParseResult.Status.EMPTY_COMMAND) {
                replies.sendReply(PendingMessage.ofString("请输入指令。使用/help获取帮助。"));
                return;
            }

            Context context = parseResult.context().withReplies(replies);
            commandExecutor.execute(() -> {
                try {
                    dispatch(context);
                } catch (Exception e) {
                    context.sendReply(PendingMessage.ofString("处理指令时发生错误，请稍后再试。"));
                    LOG.error("Failed to process inbound message {}", messageId, e);
                }
            });
        } catch (Exception e) {
            taskCoordinator.sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString("处理指令时发生错误，请稍后再试。"), messageSeqCounter);
            LOG.error("Failed to process inbound message {}", messageId, e);
        }
    }

    @Getter
    private static volatile Context lastContext = null;

    private void dispatch(Context ctx) {
        lastContext = ctx;
        commandMetric.run();
        if (ctx.command().startsWith("debug.")) {
            debugRoutes.routeDebug(ctx);
            return;
        }
        commandRegistry.dispatch(ctx, unknownCommand);
    }

    private static CommandRegistry createCommandRegistry(
            BindingCommandHandler bindingCommands,
            ScoreCommandHandler scoreCommands,
            BeatmapCommandHandler beatmapCommands,
            SocialCommandHandler socialCommands,
            ReplayCommandHandler replayCommands,
            GeneralCommandHandler generalCommands,
            WatchCommandHandler watchCommands,
            SpecificScoreWatchCommandHandler specificScoreWatchCommands,
            DcsCommandHandler dcsCommands,
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
                .register(specificScoreWatchCommands::handleWx, "wx")
                .register(dcsCommands::handleDcs, "dcs")
                .register(rankGuessCommands::handleRankGuess, "rg")
                .build();
    }

    @SuppressWarnings("unused")
    public Set<String> registeredCommands() {
        return commandRegistry.registeredCommands();
    }

    private String getAccessTokenFor(String openId) {
        return Optional.ofNullable(authHelper.updateTokenAndGet(openId))
                .map(OsuToken::accessToken)
                .orElse(null);
    }

    public void sendAttachmentUploadMessage(String openId, String msgId, PendingMessage s) {
        taskCoordinator.sendOutboundMessage(openId, msgId, false, s, new AtomicInteger(10));
    }

}
