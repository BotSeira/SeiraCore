package xyz.zcraft.seira.command.target;

import java.util.Objects;

/** A concrete identity, never a moving shortcut or an untyped number. */
public record TargetId(TargetKind kind, String value) {
    public TargetId {
        Objects.requireNonNull(kind);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Target ID is required");
    }

    public static TargetId beatmap(long id) { return new TargetId(TargetKind.BEATMAP, Long.toString(id)); }
    public static TargetId beatmapset(long id) { return new TargetId(TargetKind.BEATMAPSET, Long.toString(id)); }
    public static TargetId score(String id) { return new TargetId(TargetKind.SCORE, id); }

    public long numericId() { return Long.parseLong(value); }

    public boolean isLocalScore() {
        return kind == TargetKind.SCORE && !value.chars().allMatch(Character::isDigit);
    }
}
