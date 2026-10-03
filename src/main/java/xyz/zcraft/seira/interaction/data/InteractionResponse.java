package xyz.zcraft.seira.interaction.data;

import lombok.Getter;

public enum InteractionResponse {
    SUCCESS(0),
    FAILED(1),
    RATE_LIMITED(2),
    DUPLICATED(3),
    UNAUTHORIZED(4),
    ADMIN_ONLY(5);

    @Getter
    private final int code;

    InteractionResponse(int code) {
        this.code = code;
    }
}
