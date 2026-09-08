package xyz.zcraft.seira.command.target;

import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.parse.ShortcutTarget;

/** Boundary between the existing command/API syntax and typed target queries. */
public final class TargetAdapter {
    private TargetAdapter() {}

    public static TargetQuery fromShortcut(ShortcutTarget target, TargetKind numericKind) {
        if (target == null) throw new ResolutionException("请指定指令目标喵");
        if (target.isError()) throw new ResolutionException(target.errorMessage());
        if (target.isLocalScore()) return new TargetQuery.Reference(TargetId.score(target.localScoreId()), null);
        return switch (target.macroType()) {
            case null -> new TargetQuery.Reference(new TargetId(numericKind, target.explicitId().toString()), null);
            case "m" -> new TargetQuery.Reference(TargetId.beatmap(target.explicitId()), target.userRef());
            case "s" -> new TargetQuery.Reference(TargetId.score(target.explicitId().toString()), target.userRef());
            case "ms" -> target.macroIndex() == null
                    ? new TargetQuery.Reference(TargetId.beatmapset(target.explicitId()), target.userRef())
                    : new TargetQuery.Difficulty(target.explicitId(), target.macroIndex(), target.userRef());
            case "rs" -> new TargetQuery.ScoreList(TargetQuery.ScoreSource.RECENT, target.macroIndex(), target.userRef());
            case "rp" -> new TargetQuery.ScoreList(TargetQuery.ScoreSource.RECENT_PASSED, target.macroIndex(), target.userRef());
            case "bp" -> new TargetQuery.ScoreList(TargetQuery.ScoreSource.BEST, target.macroIndex(), target.userRef());
            case "mp" -> new TargetQuery.Room(target.userRef());
            default -> throw new ResolutionException("未知的快捷查询");
        };
    }

    public static ShortcutTarget toShortcut(TargetId target) {
        return toShortcut(new TargetQuery.Reference(target, null));
    }

    public static ShortcutTarget toShortcut(TargetQuery query) {
        return switch (query) {
            case TargetQuery.Reference ref -> {
                TargetId id = ref.target();
                if (id.isLocalScore()) yield ShortcutTarget.localScore(id.value());
                String type = switch (id.kind()) {
                    case BEATMAP -> "m";
                    case BEATMAPSET -> "ms";
                    case SCORE -> "s";
                };
                yield new ShortcutTarget(id.numericId(), ref.user(), type, null, null);
            }
            case TargetQuery.ScoreList list -> {
                String type = switch (list.source()) {
                    case RECENT -> "rs";
                    case RECENT_PASSED -> "rp";
                    case BEST -> "bp";
                };
                yield new ShortcutTarget(null, list.user(), type, list.index(), null);
            }
            case TargetQuery.Difficulty difficulty ->
                    new ShortcutTarget(difficulty.setId(), difficulty.user(), "ms", difficulty.index(), null);
            case TargetQuery.Room room -> new ShortcutTarget(null, room.user(), "mp", null, null);
        };
    }
}
