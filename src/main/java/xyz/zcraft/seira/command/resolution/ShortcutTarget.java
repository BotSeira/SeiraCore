package xyz.zcraft.seira.command.resolution;

import xyz.zcraft.seira.data.UserRef;

public record ShortcutTarget(
        Long explicitId,
        UserRef userRef,
        String macroType,
        Long macroIndex,
        String errorMessage
) {
    public boolean isMacro() {
        return macroType != null;
    }

    public boolean isError() {
        return errorMessage != null;
    }
}
