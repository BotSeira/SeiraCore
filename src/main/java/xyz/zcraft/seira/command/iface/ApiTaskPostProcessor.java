package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.bot.data.PendingMessage;

@FunctionalInterface
public interface ApiTaskPostProcessor {
    PendingMessage execute();
}
