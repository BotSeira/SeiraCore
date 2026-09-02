package xyz.zcraft.seira.api.data;

import lombok.Getter;

@Getter
public enum ErrorCode {
    NO_BEATMAP_FOUND(1001),
    NO_BEATMAPSET_FOUND(1002),
    NO_USER_FOUND(1003),
    NO_SCORE_FOUND(1004),
    NO_ROOM_FOUND(1005),
    NO_BACKGROUND_FOUND(1006),

    ILLEGAL_ARGUMENT(2001),
    UNAUTHORIZED(2002),

    FETCH_FAILED(3000),
    BEATMAP_FETCH_FAILED(3001),
    BEATMAPSET_FETCH_FAILED(3002),
    USER_FETCH_FAILED(3003),
    SCORE_FETCH_FAILED(3004),
    ROOM_FETCH_FAILED(3005),
    IMAGE_FETCH_FAILED(3006),
    REPLAY_FETCH_FAILED(3007),
    TOKEN_FETCH_FAILED(3008),

    REPLAY_UNAVAILABLE(4001),
    BEATMAP_PARSE_FAILED(4002),
    SCORE_PARSE_FAILED(4003),
    REPLAY_PARSE_FAILED(4004),
    REPLAY_UPLOAD_FAILED(4005),

    RENDER_QUEUE_FULL(5001),
    RENDERER_UNAVAILABLE(5002),
    PERFORMANCE_PLUS_UNAVAILABLE(5003);

    private final int code;

    ErrorCode(int i) {
        this.code = i;
    }

    public static ErrorCode fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        return null;
    }
}
