package xyz.zcraft.seira.command.target;

import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.parse.*;
import xyz.zcraft.seira.data.UserRef;
import java.util.List;
import java.util.function.Predicate;
import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

/** Shared command boundary: parse and validate now, resolve in the request worker later. */
public final class CommandTargets {
    public record Arguments(TargetRequest request, int consumedArgs) {
        public String nextArgument(Context context) {
            return context.argumentCount() > consumedArgs ? context.argument(consumedArgs) : null;
        }
    }

    private final Resolver resolver;
    private final TargetService service;

    public CommandTargets(Resolver resolver, TargetService service) {
        this.resolver = resolver;
        this.service = service;
    }

    public Arguments parse(Context context, TargetKind kind, String usage, int maxRemaining) {
        return parse(context, kind, usage, maxRemaining, _ -> false);
    }

    public Arguments parse(Context context, TargetKind kind, String usage, int maxRemaining,
                           Predicate<String> optional) {
        return parse(context, kind, usage, maxRemaining, optional, false);
    }

    public Arguments parseShowcase(Context context, String usage) {
        return parse(context, TargetKind.BEATMAP, usage, Integer.MAX_VALUE,
                arg -> arg.startsWith("+") || arg.startsWith("="), true);
    }

    private Arguments parse(Context context, TargetKind kind, String usage, int maxRemaining,
                            Predicate<String> optional, boolean showcase) {
        try {
            int consumed = 0;
            TargetRequest request;
            if (context.argumentCount() == 0 || optional.test(context.argument(0))) {
                request = showcase ? service.recallShowcase(context.senderUserId()) : service.recall(context.senderUserId(), kind);
            } else {
                TargetResolution parsed = resolver.resolveTargetWithOptionalMention(context.args(), context.senderUserId());
                consumed = parsed.consumedArgs();
                request = new TargetRequest(kind, TargetAdapter.fromShortcut(parsed.target(), kind), false);
            }
            if (request == null || context.argumentCount() - consumed > maxRemaining) {
                throw new ResolutionException(usage);
            }
            return new Arguments(request, consumed);
        } catch (ResolutionException e) {
            return reject(context, e.getMessage());
        }
    }

    public Arguments parseScore(Context context, String usage) {
        if (context.argumentCount() > 2) return reject(context, usage);
        try {
            if (context.argumentCount() == 1 && resolver.looksLikeMention(context.argument(0))) {
                UserRef user = user(context.argument(0), usage);
                TargetRequest request = service.recall(context.senderUserId(), TargetKind.SCORE);
                if (request == null) throw new ResolutionException(usage);
                return new Arguments(request.withUser(user), 1);
            }
            Arguments parsed = parse(context, TargetKind.SCORE, usage, 1);
            if (parsed == null || parsed.nextArgument(context) == null) return parsed;
            UserRef user = user(parsed.nextArgument(context), usage);
            return new Arguments(parsed.request().withUser(user), parsed.consumedArgs() + 1);
        } catch (ResolutionException e) {
            return reject(context, e.getMessage());
        }
    }

    private UserRef user(String argument, String usage) {
        UserRefResolution parsed = resolver.resolveUserRefArgument(argument);
        if (parsed.errorMessage() != null) throw new ResolutionException(parsed.errorMessage());
        if (parsed.userRef() == null) throw new ResolutionException(usage);
        return parsed.userRef();
    }

    private Arguments reject(Context context, String message) {
        context.sendReply(PendingMessage.ofMarkdownRaw(at(context) + message));
        return null;
    }

    public ShortcutTarget resolve(Context context, Arguments arguments) {
        return TargetAdapter.toShortcut(service.resolve(context.senderUserId(), arguments.request()));
    }

    public ShortcutTarget resolveShowcase(Context context, Arguments arguments) {
        TargetRequest request = arguments.request();
        if (request.isLocalScore()) request = request.withKind(TargetKind.SCORE);
        return TargetAdapter.toShortcut(service.resolve(context.senderUserId(), request));
    }

    public ShortcutTarget resolve(Context context, TargetKind kind, ShortcutTarget target) {
        return resolve(context, kind, target, List.of());
    }

    public ShortcutTarget resolve(Context context, TargetKind kind, ShortcutTarget target, List<String> filters) {
        TargetRequest request = new TargetRequest(kind, TargetAdapter.fromShortcut(target, kind), false);
        return TargetAdapter.toShortcut(service.resolve(context.senderUserId(), request, filters));
    }
}
