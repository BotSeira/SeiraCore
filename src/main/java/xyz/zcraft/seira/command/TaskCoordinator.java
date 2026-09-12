package xyz.zcraft.seira.command;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.ApiRequestException;
import xyz.zcraft.seira.api.ReplayRenderException;
import xyz.zcraft.seira.api.data.Base64Bytes;
import xyz.zcraft.seira.api.data.QqUploadRequest;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.*;
import xyz.zcraft.seira.data.SendResult;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.discord.DiscordBridgeService;
import xyz.zcraft.seira.services.ApiRequestStats;
import xyz.zcraft.seira.services.BotStat;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    public static String resolveErrorMessage(Exception exception) {
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
                case ReplayRenderException e -> {
                    return e.getMessage();
                }
                default -> {
                }
            }
            cursor = cursor.getCause();
        }
        return "请求处理失败，请稍后再试。";
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
     * Tracks queue estimates and elapsed time; the caller executes the request directly.
     */
    public RequestTiming beginRequest(Context ctx, String requestType) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        RequestTiming timing = new RequestTiming(requestType);
        try {
            ctx.sendQueueNotice(PendingMessage.ofMarkdownRaw(
                    at(ctx) + "请求已加入队列，预计等待时间" + estimatedSeconds + "秒。"));
            return timing;
        } catch (RuntimeException e) {
            timing.close();
            throw e;
        }
    }

    public PendingMessage imageMessage(Response<Base64Bytes> response, PendingMessage completion) {
        UploadedImage image = messageSender.uploadImageToCos(response.getContent().bytes());
        return combineImageAndCompletion(image, completion);
    }

    public QqUploadRequest createVideoUploadRequest(Context ctx) {
        String targetId = ctx.inGroup() ? ctx.groupId() : ctx.senderUserId();
        return messageSender.createVideoUploadRequest(targetId, ctx.inGroup());
    }

    public APIHelper.ReplayRenderResult waitForReplay(APIHelper.ReplayTaskInfo taskInfo) {
        return waitForReplay(taskInfo, -1);
    }

    public APIHelper.ReplayRenderResult waitForReplay(APIHelper.ReplayTaskInfo taskInfo, long timeout) {
        if (taskInfo == null || taskInfo.taskId() == null || taskInfo.taskId().isBlank()) {
            throw new IllegalArgumentException("回放任务未返回有效请求ID，无法获取视频结果。");
        }

        APIHelper.ReplayRenderResult result = APIHelper.waitReplayVideo(taskInfo.taskId(), timeout);
        replayResults.put(taskInfo.taskId(), result);
        BotStat.incrementReplays();
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

    @NotNull
    public SendResult sendOutboundMessage(String targetId, String messageId, boolean groupMessage, PendingMessage pendingMsg, AtomicInteger messageSeqCounter) {
        Message message = new Message();
        message.setMsgType(pendingMsg.getMsgType());
        message.setMsgId(messageId);
        if (messageSeqCounter != null) {
            message.setMsgSeq(messageSeqCounter.getAndIncrement());
        }

        if (pendingMsg.getMessageReference() != null) {
            message.setMessageReference(pendingMsg.getMessageReference());
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

        SentMessage sentMessage;
        if (groupMessage) {
            sentMessage = messageSender.sendGroupMessage(targetId, message);
        } else {
            sentMessage = messageSender.sendPrivateMessage(targetId, message);
        }

        if (sentMessage != null && groupMessage) {
            PendingMessage portableResult = uploadResult
                    ? pendingMsg
                    : PendingMessage.ofString("媒体文件上传失败");
            discordBridgeService.acceptQqCommandReply(targetId, portableResult);
        }

        return new SendResult(uploadResult && sentMessage != null, sentMessage);
    }

    public final class RequestTiming implements AutoCloseable {
        private final String requestType;
        private final long startedAt = System.nanoTime();
        private boolean closed;

        private RequestTiming(String requestType) {
            this.requestType = requestType;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            apiRequestStats.complete(requestType,
                    Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L));
        }
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
        public synchronized SendResult sendReply(PendingMessage message) {
            return sendOutboundMessage(targetId, messageId, groupMessage, message, passiveSequence);
        }

        @Override
        public synchronized SendResult sendProactive(PendingMessage message) {
            return sendOutboundMessage(targetId, null, groupMessage, message, null);
        }

        @Override
        public synchronized SendResult sendQueueNotice(PendingMessage message) {
            if (groupMessage && !queueMessageInGroup) {
                return new SendResult(true, null);
            }
            return sendReply(message);
        }

    }
}
