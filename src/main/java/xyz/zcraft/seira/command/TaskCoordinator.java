package xyz.zcraft.seira.command;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.ApiRequestException;
import xyz.zcraft.seira.api.Response;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.command.iface.*;
import xyz.zcraft.seira.data.*;
import xyz.zcraft.seira.util.ApiRequestStats;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static xyz.zcraft.seira.command.ReplyFactory.at;

final class TaskCoordinator {
    private static final Logger LOG = LogManager.getLogger(TaskCoordinator.class);

    private final MessageSender messageSender;
    private final ApiRequestStats apiRequestStats = new ApiRequestStats();

    TaskCoordinator(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    RouteDecision queueApiRequest(Context ctx, String requestType, ApiTaskExecutor executor) {
        return queueApiRequest(ctx, requestType, executor, () -> null, () -> {});
    }

    RouteDecision queueApiRequestUntilSubmit(String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor, ApiTaskFinalizer finalizer) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        PendingMessage queuedNotice = PendingMessage.ofString("请求已加入队列，预计等待时间" + estimatedSeconds + "秒。");
        return RouteDecision.async(queuedNotice, new ApiTask(requestType, executor, postProcessor, finalizer, true));
    }

    RouteDecision queueImageRequest(Context ctx, String requestType, ImageResponseCreator creator, ImageResponsePostProcessor postProcessor) {
        AtomicReference<Response<Base64Bytes>> responseRef = new AtomicReference<>();
        return queueApiRequest(
                ctx,
                requestType,
                () -> {
                    Response<Base64Bytes> response = creator.create();
                    responseRef.set(response);
                    return PendingMessage.ofImageBase64(response.getContent().toBase64());
                },
                () -> postProcessor.execute(ctx, responseRef.get()),
                () -> {
                }
        );
    }

    RouteDecision queueReplayTask(Context ctx, String requestType, ReplayTaskCreator creator, BiFunction<Context, APIHelper.ReplayTaskInfo, PendingMessage> messageCreator) {
        AtomicReference<APIHelper.ReplayTaskInfo> taskInfoRef = new AtomicReference<>();

        return queueApiRequestUntilSubmit(
                requestType,
                () -> {
                    APIHelper.ReplayTaskInfo taskInfo = creator.create();
                    taskInfoRef.set(taskInfo);

                    return messageCreator.apply(ctx, taskInfo);
                },
                () -> {
                    APIHelper.ReplayTaskInfo taskInfo = taskInfoRef.get();
                    if (taskInfo == null || taskInfo.taskId() == null || taskInfo.taskId().isBlank()) {
                        return PendingMessage.ofString("回放任务未返回有效请求ID，无法获取视频结果。请稍后重试。");
                    }

                    APIHelper.ReplayRenderResult result = APIHelper.waitReplayVideo(taskInfo.taskId());
                    if (result != null) {
                        return PendingMessage.ofVideoUrl(result.videoUrl());
                    }
                    return PendingMessage.ofString("回放视频生成失败，请稍后重试。");
                },
                () -> {
                }
        );
    }

    void processApiTask(String targetId, String messageId, boolean groupMessage, ApiTask apiTask, AtomicInteger messageSeqCounter) {
        long startedAt = System.nanoTime();
        boolean statsCompleted = false;
        try {
            PendingMessage response = apiTask.executor().execute();
            if (response != null) {
                sendOutboundMessage(targetId, messageId, groupMessage, response, messageSeqCounter);
            }

            if (apiTask.completeStatsAfterExecutor()) {
                long elapsedMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
                apiRequestStats.complete(apiTask.requestType(), elapsedMillis);
                statsCompleted = true;
            }

            PendingMessage postResponse = apiTask.postProcessor().execute();
            if (postResponse != null) {
                sendOutboundMessage(targetId, messageId, groupMessage, postResponse, messageSeqCounter);
            }
        } catch (Exception e) {
            sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString(resolveErrorMessage(e)), messageSeqCounter);
            LOG.error("Failed to execute API task for message {}", messageId, e);
        } finally {
            try {
                apiTask.finalizer().execute();
            } catch (Exception e) {
                LOG.warn("Failed to run finalizer for message {}", messageId, e);
            }
            if (!statsCompleted) {
                long elapsedMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
                apiRequestStats.complete(apiTask.requestType(), elapsedMillis);
            }
        }
    }

    void sendOutboundMessage(String targetId, String messageId, boolean groupMessage, PendingMessage pendingMsg, AtomicInteger messageSeqCounter) {
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

        if (pendingMsg.getFileUrl() != null) {
            LOG.info("Uploading media for {}", messageId);
            FileInfo fileInfo = groupMessage
                    ? messageSender.uploadGroupMedia(targetId, pendingMsg.getFileType(), pendingMsg.getFileUrl())
                    : messageSender.uploadPrivateMedia(targetId, pendingMsg.getFileType(), pendingMsg.getFileUrl());
            if (fileInfo == null) {
                LOG.error("Failed to upload media for message {}", messageId);
                message.setContent("媒体文件上传失败");
                message.setMsgType(0);
            } else {
                LOG.info("Media uploaded for message {}", messageId);
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
            } else {
                LOG.info("Base64 media uploaded for message {}", messageId);
                message.setMedia(fileInfo);
            }
        }

        if (groupMessage) {
            messageSender.sendGroupMessage(targetId, message);
        } else {
            messageSender.sendPrivateMessage(targetId, message);
        }
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
                    String mapped = ApiRequestException.getDefaultMessage(e.getErrorCode());
                    if (mapped != null) {
                        return mapped;
                    }

                    String rawMessage = e.getMessage();
                    if (rawMessage != null && !rawMessage.isBlank()) {
                        return rawMessage;
                    }
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
