package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.data.PendingMessage;

@FunctionalInterface
public interface ApiTaskExecutor {
    PendingMessage execute();
}
