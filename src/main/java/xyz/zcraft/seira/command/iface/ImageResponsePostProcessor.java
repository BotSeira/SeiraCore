package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;

@FunctionalInterface
public interface ImageResponsePostProcessor {
    PendingMessage execute(Context ctx, Response<?> response);
}
