package xyz.zcraft.seira.command.target;

/** Immutable associations for the caller's current target. */
public record TargetMemory(TargetId beatmapset, TargetId beatmap, TargetId score) {
    public static final TargetMemory EMPTY = new TargetMemory(null, null, null);

    public TargetId select(TargetKind kind) {
        TargetId exact = switch (kind) {
            case BEATMAPSET -> beatmapset;
            case BEATMAP -> beatmap;
            case SCORE -> score;
        };
        if (exact != null) return exact;
        if (beatmap != null) return beatmap;
        return score != null ? score : beatmapset;
    }

    public boolean contains(TargetId target) {
        return target.equals(beatmapset) || target.equals(beatmap) || target.equals(score);
    }

    public TargetMemory with(TargetId target) {
        return switch (target.kind()) {
            case BEATMAPSET -> new TargetMemory(target, beatmap, score);
            case BEATMAP -> new TargetMemory(beatmapset, target, score);
            case SCORE -> new TargetMemory(beatmapset, beatmap, target);
        };
    }
}
