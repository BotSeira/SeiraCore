package xyz.zcraft.seira.api;

import lombok.Getter;
import xyz.zcraft.seira.data.ErrorCode;

@Getter
public class ApiRequestException extends RuntimeException {
    private final Integer errorCode;

    public ApiRequestException(Integer errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static String getDefaultMessage(Integer code) {
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
            case UNAUTHORIZED -> "缺少用户凭据，请重新绑定。";

            case BEATMAP_FETCH_FAILED -> "获取铺面数据失败，请稍后重试。";
            case BEATMAPSET_FETCH_FAILED -> "获取铺面集数据失败，请稍后重试。";
            case USER_FETCH_FAILED -> "获取玩家数据失败，请稍后重试。";
            case SCORE_FETCH_FAILED -> "获取成绩数据失败，请稍后重试。";
            case IMAGE_FETCH_FAILED -> "获取图片数据失败，请稍后重试。";
            case ROOM_FETCH_FAILED -> "获取多人房间数据失败，请稍后重试。";
            case REPLAY_FETCH_FAILED -> "获取回放数据失败，请稍后重试。";
            case FETCH_FAILED -> "数据获取失败，请稍后重试。";
            case TOKEN_FETCH_FAILED -> "令牌获取失败。";

            case REPLAY_UNAVAILABLE -> "该成绩暂不支持回放渲染。";
            case RENDER_QUEUE_FULL -> "回放渲染队列已满，请稍后再试。";

            case ROSU_ERROR -> "oStella API 发生 Rosu 错误，请稍后重试。";
        };
    }
}

