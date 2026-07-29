package xyz.zcraft.seira.api;

import lombok.Getter;
import xyz.zcraft.seira.api.data.ErrorCode;

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
            return "发生了一个未知错误喵";
        }

        return switch (errorCode) {
            case NO_BEATMAP_FOUND -> "未找到对应谱面，请检查输入后重试喵";
            case NO_BEATMAPSET_FOUND -> "未找到对应谱面集，请检查输入后重试喵";
            case NO_USER_FOUND -> "未找到对应玩家，请检查玩家ID后重试喵";
            case NO_SCORE_FOUND -> "未找到对应成绩，请检查输入后重试喵";
            case NO_ROOM_FOUND -> "当前没有可用的多人房间信息喵";
            case NO_BACKGROUND_FOUND -> "未在缓存中找到铺面背景喵";

            case ILLEGAL_ARGUMENT -> "请求参数不合法，请检查指令参数格式喵";
            case UNAUTHORIZED -> "缺少用户凭据，请重新绑定喵";

            case BEATMAP_FETCH_FAILED -> "获取谱面数据失败，请稍后重试喵";
            case BEATMAPSET_FETCH_FAILED -> "获取谱面集数据失败，请稍后重试喵";
            case USER_FETCH_FAILED -> "获取玩家数据失败，请稍后重试喵";
            case SCORE_FETCH_FAILED -> "获取成绩数据失败，请稍后重试喵";
            case IMAGE_FETCH_FAILED -> "获取图片数据失败，请稍后重试喵";
            case ROOM_FETCH_FAILED -> "获取多人房间数据失败，请稍后重试喵";
            case REPLAY_FETCH_FAILED -> "获取回放数据失败，请稍后重试喵";
            case FETCH_FAILED -> "数据获取失败，请稍后重试喵";
            case TOKEN_FETCH_FAILED -> "令牌获取失败喵";

            case REPLAY_UNAVAILABLE -> "该成绩回放不可用喵";
            case BEATMAP_PARSE_FAILED -> "谱面数据解析失败，请稍后重试喵";
            case SCORE_PARSE_FAILED -> "成绩数据解析失败，请稍后重试喵";
            case REPLAY_PARSE_FAILED -> "回放数据解析失败，请稍后重试喵";
            case RENDER_QUEUE_FULL -> "回放渲染队列已满，请稍后再试喵";
            case REPLAY_UPLOAD_FAILED -> "回放上传失败，请稍后再试喵";
        };
    }

    public String getDefaultMessage() {
        return getDefaultMessage(this.errorCode);
    }
}

