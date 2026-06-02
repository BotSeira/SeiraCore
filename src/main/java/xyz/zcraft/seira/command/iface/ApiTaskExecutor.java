package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.bot.data.PendingMessage;

@FunctionalInterface
public interface ApiTaskExecutor {
    PendingMessage execute();
}
