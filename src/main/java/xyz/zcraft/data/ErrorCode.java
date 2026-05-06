package xyz.zcraft.data;

import lombok.Getter;

@Getter
public enum ErrorCode {
    NO_BEATMAP_FOUND(1001),
    NO_BEATMAPSET_FOUND(1002),
    NO_USER_FOUND(1003),
    NO_SCORE_FOUND(1004),
    NO_ROOM_FOUND(1005),

    ILLEGAL_ARGUMENT(2001),
    ILLEGAL_SHORTCUT(2002),

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

    RENDER_QUEUE_FULL(5001),

    ROSU_ERROR(6001);

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
