package xyz.zcraft.seira.command;

import xyz.zcraft.seira.command.resolution.ShortcutTarget;
import xyz.zcraft.seira.command.resolution.TargetResolution;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

final class TargetHistory {
    private final ConcurrentMap<String, ShortcutTarget> targets = new ConcurrentHashMap<>();

    ShortcutTarget get(String userId) {
        return targets.get(userId);
    }

    void put(String userId, ShortcutTarget target) {
        targets.put(userId, target);
    }

    TargetResolution resolveOptionalTarget(
            Context context,
            Resolver resolver,
            Predicate<String> isOptionalArgument
    ) {
        if (context.argumentCount() == 0 || isOptionalArgument.test(context.argument(0))) {
            return new TargetResolution(get(context.senderUserId()), 0);
        }
        return resolver.resolveTargetWithOptionalMention(context.args(), context.senderUserId());
    }

    void rememberExplicitTarget(Context context, TargetResolution resolution) {
        if (resolution.consumedArgs() > 0) {
            put(context.senderUserId(), resolution.target());
        }
    }
}
