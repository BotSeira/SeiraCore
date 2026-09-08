package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.data.UserRef;

/**
 * Compatibility DTO for the existing parser and API helper. TargetAdapter maps
 * this syntax to typed queries and IDs; the memory service never stores this DTO.
 * At the API boundary m means beatmap, ms without an index means beatmapset,
 * and s means online score. Bare input IDs are interpreted by the declared target kind.
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
