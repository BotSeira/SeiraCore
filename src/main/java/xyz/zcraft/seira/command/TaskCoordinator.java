package xyz.zcraft.seira.command;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.ApiRequestException;
import xyz.zcraft.seira.api.data.Base64Bytes;
import xyz.zcraft.seira.api.data.QqUploadRequest;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.MDMessage;
import xyz.zcraft.seira.bot.data.Message;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.discord.DiscordBridgeService;
import xyz.zcraft.seira.services.ApiRequestStats;
import xyz.zcraft.seira.services.BotStat;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class TaskCoordinator {
    private static final Logger LOG = LogManager.getLogger(TaskCoordinator.class);

    private final MessageSender messageSender;
    private final DiscordBridgeService discordBridgeService;
    private final ApiRequestStats apiRequestStats = new ApiRequestStats();
    private final ReplayResultStore replayResults;

    public TaskCoordinator(
            MessageSender messageSender,
            ReplayResultStore replayResults,
            DiscordBridgeService discordBridgeService
    ) {
        this.messageSender = messageSender;
        this.replayResults = replayResults;
        this.discordBridgeService = java.util.Objects.requireNonNull(discordBridgeService);
    }

    public CommandReplyChannel openReplyChannel(
            String targetId,
            String messageId,
            boolean groupMessage,
            boolean queueMessageInGroup
    ) {
        return new OutboundReplyChannel(targetId, messageId, groupMessage, queueMessageInGroup);
    }

    /**
     * Runs a reusable queued API flow while leaving the number, type and timing
     * of its replies entirely under the command handler's control.
     *
     * @return whether the action completed without throwing
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean runApiRequest(Context ctx, String requestType, Runnable action) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        ctx.sendQueueNotice(PendingMessage.ofMarkdownRaw(
                at(ctx) + "请求已加入队列，预计等待时间" + estimatedSeconds + "秒。"
        ));

        long startedAt = System.nanoTime();
        try {
            action.run();
            return true;
        } catch (Exception e) {
            ctx.sendReply(PendingMessage.ofString(resolveErrorMessage(e)));
            String message = e.getMessage();
            if (e instanceof ApiRequestException apiException) {
                message += " - " + apiException.getDefaultMessage();
            }
            LOG.error("Failed to execute command flow {}: {}", requestType, message, e);
            return false;
        } finally {
            long elapsedMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            apiRequestStats.complete(requestType, elapsedMillis);
        }
    }

    public QqUploadRequest createVideoUploadRequest(Context ctx) {
        String targetId = ctx.inGroup() ? ctx.groupId() : ctx.senderUserId();
        return messageSender.createVideoUploadRequest(targetId, ctx.inGroup());
    }

    public APIHelper.ReplayRenderResult waitForReplay(APIHelper.ReplayTaskInfo taskInfo) {
        if (taskInfo == null || taskInfo.taskId() == null || taskInfo.taskId().isBlank()) {
            throw new IllegalArgumentException("回放任务未返回有效请求ID，无法获取视频结果。");
        }

        APIHelper.ReplayRenderResult result = APIHelper.waitReplayVideo(taskInfo.taskId());
        if (result != null) {
            replayResults.put(taskInfo.taskId(), result);
            BotStat.incrementReplays();
        }
        return result;
    }

    public PendingMessage replayVideoMessage(APIHelper.ReplayRenderResult result) {
        if (result == null) {
            return PendingMessage.ofString("回放视频生成失败，请稍后重试。");
        }
        return result.qqFile() != null
                ? PendingMessage.ofUploadedVideo(result.qqFile(), result.videoUrl())
                : PendingMessage.ofVideoUrl(result.videoUrl());
    }

    public void removeReplayResult(String taskId) {
        if (taskId != null) {
            replayResults.remove(taskId);
        }
    }

    public void runImageRequest(
            Context ctx,
            String requestType,
            Supplier<Response<Base64Bytes>> creator,
            BiFunction<Context, Response<?>, PendingMessage> postProcessor
    ) {
        runApiRequest(ctx, requestType, () ->
                ctx.sendReply(waitForImage(ctx, creator, postProcessor))
        );
    }

    public void runReplayRequest(
            Context ctx,
            String requestType,
            Function<QqUploadRequest, APIHelper.ReplayTaskInfo> creator,
            BiFunction<Context, APIHelper.ReplayTaskInfo, PendingMessage> taskMessageCreator
    ) {
        runApiRequest(ctx, requestType, () -> {
            APIHelper.ReplayTaskInfo taskInfo = creator.apply(createVideoUploadRequest(ctx));
            ctx.sendReply(taskMessageCreator.apply(ctx, taskInfo));

            APIHelper.ReplayRenderResult result = waitForReplay(taskInfo);
            if (result == null) {
                ctx.sendReply(PendingMessage.ofString("回放视频生成失败，请稍后重试。"));
                return;
            }

            if (ctx.sendReply(replayVideoMessage(result))) {
                removeReplayResult(taskInfo.taskId());
            }
        });
    }

    /** Waits for an image renderer and returns a sendable message without sending it. */
    private PendingMessage waitForImage(
            Context ctx,
            Supplier<Response<Base64Bytes>> creator,
            BiFunction<Context, Response<?>, PendingMessage> postProcessor
    ) {
        Response<Base64Bytes> response = creator.get();
        byte[] imageBytes = response.getContent().bytes();
        UploadedImage uploadedImage = messageSender.uploadImageToCos(imageBytes);
        PendingMessage completionMessage = postProcessor.apply(ctx, response);
        return combineImageAndCompletion(uploadedImage, completionMessage);
    }

    private PendingMessage combineImageAndCompletion(UploadedImage image, PendingMessage completionMessage) {
        String imageMarkdown = image.toMarkdown();
        if (completionMessage instanceof MDMessage md) {
            return PendingMessage.ofMarkdownRaw(
                    imageMarkdown + "\n" + md.getMarkdown(),
                    md.getButtons()
            );
        }

        String completionContent = completionMessage == null ? null : completionMessage.getContent();
        return PendingMessage.ofMarkdownRaw(
                completionContent == null || completionContent.isBlank()
                        ? imageMarkdown
                        : imageMarkdown + "\n" + completionContent
        );
    }

    public boolean sendOutboundMessage(String targetId, String messageId, boolean groupMessage, PendingMessage pendingMsg, AtomicInteger messageSeqCounter) {
        Message message = new Message();
        message.setMsgType(pendingMsg.getMsgType());
        message.setMsgId(messageId);
        if (messageSeqCounter != null) {
            message.setMsgSeq(messageSeqCounter.getAndIncrement());
        }

        if (pendingMsg instanceof MDMessage md) {
            message.setMsgType(PendingMessage.MSG_TYPE_MARKDOWN);
            message.setMarkdown(new Gson().toJsonTree(Map.of("content", md.getMarkdown())).getAsJsonObject());
            if (md.hasKeyboard()) {
                message.setKeyboard(md.getKeyboard());
            }
        } else if (pendingMsg.getMsgType() == PendingMessage.MSG_TYPE_MARKDOWN) {
            message.setMarkdown(new Gson().toJsonTree(Map.of("content", pendingMsg.getContent())).getAsJsonObject());
        } else {
            message.setContent(pendingMsg.getContent());
        }

        boolean uploadResult = true;
        if (pendingMsg.getUploadedMedia() != null) {
            message.setMedia(pendingMsg.getUploadedMedia());
        } else if (pendingMsg.getFileUrl() != null) {
            LOG.info("Uploading media for {}", messageId);
            FileInfo fileInfo = groupMessage
                    ? messageSender.uploadGroupMedia(targetId, pendingMsg.getFileType(), pendingMsg.getFileUrl(), pendingMsg.isUpload())
                    : messageSender.uploadPrivateMedia(targetId, pendingMsg.getFileType(), pendingMsg.getFileUrl(), pendingMsg.isUpload());
            if (fileInfo == null) {
                LOG.error("Failed to upload media for message {}", messageId);
                message.setContent("媒体文件上传失败");
                message.setMsgType(0);
                uploadResult = false;
            } else {
                LOG.debug("Media uploaded for message {}", messageId);
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
                uploadResult = false;
            } else {
                LOG.debug("Base64 media uploaded for message {}", messageId);
                message.setMedia(fileInfo);
            }
        }

        boolean sendResult;
        if (groupMessage) {
            sendResult = messageSender.sendGroupMessage(targetId, message);
        } else {
            sendResult = messageSender.sendPrivateMessage(targetId, message);
        }

        if (sendResult && groupMessage) {
            PendingMessage portableResult = uploadResult
                    ? pendingMsg
                    : PendingMessage.ofString("媒体文件上传失败");
            discordBridgeService.acceptQqCommandReply(targetId, portableResult);
        }

        return uploadResult && sendResult;
    }

    private final class OutboundReplyChannel implements CommandReplyChannel {
        private final String targetId;
        private final String messageId;
        private final boolean groupMessage;
        private final boolean queueMessageInGroup;
        private final AtomicInteger passiveSequence = new AtomicInteger(1);

        private OutboundReplyChannel(
                String targetId,
                String messageId,
                boolean groupMessage,
                boolean queueMessageInGroup
        ) {
            this.targetId = targetId;
            this.messageId = messageId;
            this.groupMessage = groupMessage;
            this.queueMessageInGroup = queueMessageInGroup;
        }

        @Override
        public synchronized boolean sendReply(PendingMessage message) {
            return sendOutboundMessage(targetId, messageId, groupMessage, message, passiveSequence);
        }

        @Override
        public synchronized boolean sendProactive(PendingMessage message) {
            return sendOutboundMessage(targetId, null, groupMessage, message, null);
        }

        @Override
        public synchronized boolean sendQueueNotice(PendingMessage message) {
            if (groupMessage && !queueMessageInGroup) {
                return true;
            }
            return sendReply(message);
        }

    }

    private String resolveErrorMessage(Exception exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            switch (cursor) {
                case ApiRequestException e -> {
                    return ApiRequestException.getDefaultMessage(e.getErrorCode());
                }
                case ClosedChannelException _ -> {
                    return "oStella API 无法连接，请稍后再试。";
                }
                case ResolutionException e -> {
                    return e.getMessage();
                }
                default -> {
                }
            }
            cursor = cursor.getCause();
        }
        return "请求处理失败，请稍后再试。";
    }
}
