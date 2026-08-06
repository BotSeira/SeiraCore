package xyz.zcraft.seira.command;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.ApiRequestException;
import xyz.zcraft.seira.api.data.ApiTask;
import xyz.zcraft.seira.api.data.Base64Bytes;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.MDMessage;
import xyz.zcraft.seira.bot.data.Message;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.iface.*;
import xyz.zcraft.seira.command.route.RouteDecision;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.services.ApiRequestStats;
import xyz.zcraft.seira.services.BotStat;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class TaskCoordinator {
    private static final Logger LOG = LogManager.getLogger(TaskCoordinator.class);

    private final MessageSender messageSender;
    private final ApiRequestStats apiRequestStats = new ApiRequestStats();
    private final ReplayResultStore replayResults;

    public TaskCoordinator(MessageSender messageSender, ReplayResultStore replayResults) {
        this.messageSender = messageSender;
        this.replayResults = replayResults;
    }

    public RouteDecision queueApiRequest(Context ctx, String requestType, ApiTaskExecutor executor) {
        return queueApiRequest(ctx, requestType, executor, () -> null, (_) -> null);
    }

    RouteDecision queueApiRequestUntilSubmit(String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor, ApiTaskFinalizer finalizer) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        PendingMessage queuedNotice = PendingMessage.ofString("请求已加入队列，预计等待时间" + estimatedSeconds + "秒。");
        return RouteDecision.async(queuedNotice, new ApiTask(requestType, executor, postProcessor, finalizer, true));
    }

    public RouteDecision queueImageRequest(Context ctx, String requestType, ImageResponseCreator creator, ImageResponsePostProcessor postProcessor) {
        return queueApiRequest(
                ctx,
                requestType,
                () -> {
                    Response<Base64Bytes> response = creator.create();
                    byte[] imageBytes = response.getContent().bytes();
                    final UploadedImage uploadedImage = messageSender.uploadImageToCos(imageBytes);
                    PendingMessage completionMessage = postProcessor.execute(ctx, response);
                    return combineImageAndCompletion(uploadedImage, completionMessage);
                },
                () -> null,
                (_) -> null
        );
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

    public RouteDecision queueReplayTask(Context ctx, String requestType, ReplayTaskCreator creator, BiFunction<Context, APIHelper.ReplayTaskInfo, PendingMessage> messageCreator) {
        return queueReplayTask(ctx, requestType, creator, messageCreator, (_) -> null);
    }

    public RouteDecision queueReplayTask(
            Context ctx,
            String requestType,
            ReplayTaskCreator creator,
            BiFunction<Context, APIHelper.ReplayTaskInfo, PendingMessage> messageCreator,
            Function<Boolean, PendingMessage> completion
    ) {
        AtomicReference<APIHelper.ReplayTaskInfo> taskInfoRef = new AtomicReference<>();
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<APIHelper.ReplayRenderResult> renderResultRef = new AtomicReference<>();
        return queueApiRequestUntilSubmit(
                requestType,
                () -> {
                    String targetId = ctx.inGroup() ? ctx.groupId() : ctx.senderUserId();
                    var qqUpload = messageSender.createVideoUploadRequest(targetId, ctx.inGroup());
                    APIHelper.ReplayTaskInfo taskInfo = creator.create(qqUpload);
                    taskInfoRef.set(taskInfo);

                    return messageCreator.apply(ctx, taskInfo);
                },
                () -> {
                    APIHelper.ReplayTaskInfo taskInfo = taskInfoRef.get();
                    if (taskInfo == null || taskInfo.taskId() == null || taskInfo.taskId().isBlank()) {
                        return PendingMessage.ofString("回放任务未返回有效请求ID，无法获取视频结果。请稍后重试。");
                    }

                    taskId.set(taskInfo.taskId());
                    APIHelper.ReplayRenderResult result = APIHelper.waitReplayVideo(taskInfo.taskId());
                    if (result != null) {
                        renderResultRef.set(result);
                        replayResults.put(taskInfo.taskId(), result);
                        BotStat.incrementReplays();
                        return result.qqFile() != null
                                ? PendingMessage.ofUploadedVideo(result.qqFile())
                                : PendingMessage.ofVideoUrl(result.videoUrl());
                    }
                    return PendingMessage.ofString("回放视频生成失败，请稍后重试。");
                },
                (b) -> {
                    LOG.debug("Running finalizer of {} for request {}", b, taskId.get());
                    if (b) replayResults.remove(taskId.get());
                    return completion.apply(b && renderResultRef.get() != null);
                }
        );
    }

    public void processApiTask(String targetId, String messageId, boolean groupMessage, ApiTask apiTask, AtomicInteger messageSeqCounter) {
        long startedAt = System.nanoTime();
        boolean statsCompleted = false;
        boolean responseSent = false;
        try {
            PendingMessage response = apiTask.executor().execute();
            if (response != null) {
                responseSent = sendOutboundMessage(targetId, messageId, groupMessage, response, messageSeqCounter);
            }

            if (apiTask.completeStatsAfterExecutor()) {
                long elapsedMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
                apiRequestStats.complete(apiTask.requestType(), elapsedMillis);
                statsCompleted = true;
            }

            if (apiTask.postProcessor() != null) {
                PendingMessage postResponse = apiTask.postProcessor().execute();
                if (postResponse != null) {
                    responseSent &= sendOutboundMessage(targetId, messageId, groupMessage, postResponse, messageSeqCounter);
                }
            }
        } catch (Exception e) {
            sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString(resolveErrorMessage(e)), messageSeqCounter);
            String msg = e.getMessage();
            if (e instanceof ApiRequestException ex) {
                msg += " - " + ex.getDefaultMessage();
            }
            LOG.error("Failed to execute API task: {}", msg, e);
        } finally {
            try {
                final PendingMessage execute = apiTask.finalizer().execute(responseSent);
                if (execute != null) {
                    sendOutboundMessage(targetId, messageId, groupMessage, execute, messageSeqCounter);
                }
            } catch (Exception e) {
                LOG.warn("Failed to run finalizer: {}", e.getMessage(), e);
            }
            if (!statsCompleted) {
                long elapsedMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
                apiRequestStats.complete(apiTask.requestType(), elapsedMillis);
            }
        }
    }

    public boolean sendOutboundMessage(String targetId, String messageId, boolean groupMessage, PendingMessage pendingMsg, AtomicInteger messageSeqCounter) {
        Message message = new Message();
        message.setMsgType(pendingMsg.getMsgType());
        message.setMsgId(messageId);
        message.setMsgSeq(messageSeqCounter.getAndIncrement());

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

        return uploadResult && sendResult;
    }

    private RouteDecision queueApiRequest(Context ctx, String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor, ApiTaskFinalizer finalizer) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        PendingMessage queuedNotice = PendingMessage.ofMarkdownRaw(at(ctx) + "请求已加入队列，预计等待时间" + estimatedSeconds + "秒。");
        return RouteDecision.async(queuedNotice, new ApiTask(requestType, executor, postProcessor, finalizer, false));
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
