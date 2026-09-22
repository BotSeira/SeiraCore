package xyz.zcraft.seira.command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.seira.api.ApiHelper;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class TaskCoordinator {
    private static final Logger LOG = LogManager.getLogger(TaskCoordinator.class);
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor();
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
                    return "oStella API 无法连接，请稍后再试喵";
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
        return "请求处理失败，请稍后再试喵";
    }

    public ReplyChannel openReplyChannel(
            String targetId, String messageId, boolean groupMessage, boolean queueMessageInGroup, String refMsgIdx
    ) {
        return new ReplyChannel(this, targetId, messageId, groupMessage, queueMessageInGroup, refMsgIdx);
    }

    public RequestTiming beginRequest(Context ctx, String requestType, boolean timeoutNotify) {
        return beginRequest(ctx, requestType, 60, timeoutNotify ? "请求处理时间超过预期，这可能是由于相关数据缺少缓存，请耐心等待喵。" : null);
    }

    public RequestTiming beginRequest(Context ctx, String requestType, int timeout, String timeoutNotify) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        ScheduledFuture<?> schedule = null;

        if (timeoutNotify != null && !timeoutNotify.isBlank()) {
            schedule = TIMEOUT_SCHEDULER.schedule(
                    () -> ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + timeoutNotify)),
                    timeout, TimeUnit.SECONDS
            );
        }

        RequestTiming timing = new RequestTiming(requestType, schedule);

        try {
            ctx.sendQueueNotice(PendingMessage.ofMarkdownRaw(at(ctx) + "请求已加入队列，预计等待时间" + estimatedSeconds + "秒。"));
            return timing;
        } catch (RuntimeException e) {
            timing.close();
            throw e;
        }
    }

    public RequestTiming beginRequest(Context ctx, String requestType) {
        return beginRequest(ctx, requestType, true);
    }

    public PendingMessage imageMessage(Response<Base64Bytes> response, PendingMessage completion) {
        UploadedImage image = messageSender.uploadImageToCos(response.getContent().bytes());
        return combineImageAndCompletion(image, completion);
    }

    public QqUploadRequest createVideoUploadRequest(Context ctx) {
        String targetId = ctx.inGroup() ? ctx.groupId() : ctx.senderUserId();
        return messageSender.createVideoUploadRequest(targetId, ctx.inGroup());
    }

    public ApiHelper.ReplayRenderResult waitForReplay(ApiHelper.ReplayTaskInfo taskInfo) {
        return waitForReplay(taskInfo, -1);
    }

    public ApiHelper.ReplayRenderResult waitForReplay(ApiHelper.ReplayTaskInfo taskInfo, long timeout) {
        if (taskInfo == null || taskInfo.taskId() == null || taskInfo.taskId().isBlank()) {
            throw new IllegalArgumentException("回放任务未返回有效请求ID，无法获取视频结果。");
        }

        ApiHelper.ReplayRenderResult result = ApiHelper.waitReplayVideo(taskInfo.taskId(), timeout);
        replayResults.put(taskInfo.taskId(), result);
        BotStat.incrementReplays();
        return result;
    }

    public PendingMessage replayVideoMessage(ApiHelper.ReplayRenderResult result) {
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
            message.setMarkdown(Message.MessageMarkdown.of(md.getMarkdown()));
            if (md.hasKeyboard()) {
                message.setKeyboard(md.getKeyboard());
            }
        } else if (pendingMsg.getMsgType() == PendingMessage.MSG_TYPE_MARKDOWN) {
            message.setMarkdown(Message.MessageMarkdown.of(pendingMsg.getContent()));
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

        final boolean success = uploadResult && sentMessage != null;

        return new SendResult(success, sentMessage);
    }

    public final class RequestTiming implements AutoCloseable {
        private final String requestType;
        private final long startedAt = System.nanoTime();
        private final ScheduledFuture<?> scheduledFuture;
        private boolean closed;

        private RequestTiming(String requestType, ScheduledFuture<?> schedule) {
            this.requestType = requestType;
            this.scheduledFuture = schedule;
        }

        @Override
        public void close() {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
            }

            if (closed) return;
            closed = true;
            apiRequestStats.complete(requestType,
                    Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L));
        }
    }
}
