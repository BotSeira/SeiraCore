package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.data.UserRef;

/**
 * 用户输入或 API 目标。解析后的普通目标只包含 explicitId；
 * 本地成绩使用 localScoreId。跨类型查找时 m/ms/s 标明原始 ID 的类型。
 */
public record ShortcutTarget(
        Long explicitId,
        String localScoreId,
        UserRef userRef,
        String macroType,
        Long macroIndex,
        String errorMessage
) {
    public ShortcutTarget(Long explicitId, UserRef userRef, String macroType, Long macroIndex, String errorMessage) {
        this(explicitId, null, userRef, macroType, macroIndex, errorMessage);
    }

    public static ShortcutTarget localScore(String localScoreId) {
        return new ShortcutTarget(null, localScoreId, null, null, null, null);
    }

    public boolean isMacro() {
        return macroType != null;
    }

    public boolean isError() {
        return errorMessage != null;
    }

    public boolean isLocalScore() {
        return localScoreId != null;
    }
}
