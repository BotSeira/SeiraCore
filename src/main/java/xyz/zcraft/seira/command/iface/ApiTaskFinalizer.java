package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.bot.data.PendingMessage;

@FunctionalInterface
public interface ApiTaskFinalizer {
    PendingMessage execute(boolean result);
}

