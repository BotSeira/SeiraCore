package xyz.zcraft.seira.command.resolution;

public record ShortcutTarget(
        Long explicitId,
        Long boundUid,
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