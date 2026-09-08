package xyz.zcraft.seira.command.target;

import xyz.zcraft.seira.data.UserRef;

/** The four supported ways to select a target. Player overrides are explicit. */
public sealed interface TargetQuery {
    UserRef user();

    record Reference(TargetId target, UserRef user) implements TargetQuery {}
    record ScoreList(ScoreSource source, long index, UserRef user) implements TargetQuery {}
    record Difficulty(long setId, long index, UserRef user) implements TargetQuery {}
    record Room(UserRef user) implements TargetQuery {}

    enum ScoreSource { RECENT, RECENT_PASSED, BEST }

    default TargetQuery withUser(UserRef user) {
        return switch (this) {
            case Reference q -> new Reference(q.target(), user);
            case ScoreList q -> new ScoreList(q.source(), q.index(), user);
            case Difficulty q -> new Difficulty(q.setId(), q.index(), user);
            case Room _ -> new Room(user);
        };
    }
}
