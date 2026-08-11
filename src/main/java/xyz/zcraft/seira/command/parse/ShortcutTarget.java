package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.data.UserRef;

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
