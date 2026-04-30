package xyz.zcraft.bot;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.api.APIHelper;
import xyz.zcraft.api.ApiRequestException;
import xyz.zcraft.data.Button;
import xyz.zcraft.data.ErrorCode;
import xyz.zcraft.data.FileInfo;
import xyz.zcraft.data.MDMessage;
import xyz.zcraft.data.Message;
import xyz.zcraft.data.PendingMessage;
import xyz.zcraft.util.ApiRequestStats;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

final class CommandTaskCoordinator {
    private static final Logger LOG = LogManager.getLogger(CommandTaskCoordinator.class);

    private final MessageSender messageSender;
    private final ApiRequestStats apiRequestStats = new ApiRequestStats();

    CommandTaskCoordinator(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    RouteDecision queueApiRequest(String requestType, ApiTaskExecutor executor) {
        return queueApiRequest(requestType, executor, () -> null, () -> {
        });
    }

    RouteDecision queueApiRequestUntilSubmit(String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor, ApiTaskFinalizer finalizer) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        PendingMessage queuedNotice = PendingMessage.ofString("请求已加入队列，预计等待时间" + estimatedSeconds + "秒。");
        return RouteDecision.async(queuedNotice, new ApiTask(requestType, executor, postProcessor, finalizer, true));
    }

    RouteDecision queueImageRequest(String requestType, ImageResponseCreator creator, ImageResponsePostProcessor postProcessor) {
        AtomicReference<APIHelper.ImageResponse> responseRef = new AtomicReference<>();
        return queueApiRequest(requestType,
                () -> {
                    APIHelper.ImageResponse response = creator.create();
                    responseRef.set(response);
                    return PendingMessage.ofImageBase64(response.getBase64());
                },
                () -> postProcessor.execute(responseRef.get()),
                () -> {
                }
        );
    }

    RouteDecision queueReplayTask(String requestType, ReplayTaskCreator creator, Function<String, List<List<Button>>> replayButtonsFactory) {
        AtomicReference<APIHelper.ReplayTaskInfo> taskInfoRef = new AtomicReference<>();

        return queueApiRequestUntilSubmit(
                requestType,
                () -> {
                    APIHelper.ReplayTaskInfo taskInfo = creator.create();
                    taskInfoRef.set(taskInfo);

                    String queuedText = "生成请求已提交。";
                    if (taskInfo.position() != null) {
                        queuedText += "\n队列位置：" + taskInfo.position();
                    }
                    if (taskInfo.taskId() != null) {
                        queuedText += "\n请求ID：" + taskInfo.taskId();
                    }
                    if (taskInfo.message() != null) {
                        queuedText += "\n" + taskInfo.message();
                    }
                    return PendingMessage.ofMarkdownRaw(queuedText, replayButtonsFactory.apply(taskInfo.taskId()));
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

    private RouteDecision queueApiRequest(String requestType, ApiTaskExecutor executor, ApiTaskPostProcessor postProcessor, ApiTaskFinalizer finalizer) {
        long estimatedSeconds = apiRequestStats.estimateAndEnqueue(requestType);
        PendingMessage queuedNotice = PendingMessage.ofString("请求已加入队列，预计等待时间" + estimatedSeconds + "秒。");
        return RouteDecision.async(queuedNotice, new ApiTask(requestType, executor, postProcessor, finalizer, false));
    }

    private String resolveErrorMessage(Exception exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            if (cursor instanceof ApiRequestException apiRequestException) {
                String mapped = mapErrorCodeMessage(apiRequestException.getErrorCode());
                if (mapped != null) {
                    return mapped;
                }

                String rawMessage = apiRequestException.getMessage();
                if (rawMessage != null && !rawMessage.isBlank()) {
                    return rawMessage;
                }
            }
            cursor = cursor.getCause();
        }
        return "请求处理失败，请稍后再试。";
    }

    private String mapErrorCodeMessage(Integer code) {
        ErrorCode errorCode = ErrorCode.fromCode(code);
        if (errorCode == null) {
            return null;
        }

        return switch (errorCode) {
            case NO_BEATMAP_FOUND -> "未找到对应铺面，请检查输入后重试。";
            case NO_BEATMAPSET_FOUND -> "未找到对应铺面集，请检查输入后重试。";
            case NO_USER_FOUND -> "未找到对应玩家，请检查玩家ID后重试。";
            case NO_SCORE_FOUND -> "未找到对应成绩，请检查输入后重试。";
            case NO_ROOM_FOUND -> "当前没有可用的多人房间信息。";
            case ILLEGAL_ARGUMENT -> "请求参数不合法，请检查指令参数格式。";
            case BEATMAP_FETCH_FAILED -> "获取铺面数据失败，请稍后重试。";
            case BEATMAPSET_FETCH_FAILED -> "获取铺面集数据失败，请稍后重试。";
            case USER_FETCH_FAILED -> "获取玩家数据失败，请稍后重试。";
            case SCORE_FETCH_FAILED -> "获取成绩数据失败，请稍后重试。";
            case REPLAY_UNAVAILABLE -> "该成绩暂不支持回放渲染。";
            case RENDER_QUEUE_FULL -> "回放渲染队列已满，请稍后再试。";
        };
    }
}


