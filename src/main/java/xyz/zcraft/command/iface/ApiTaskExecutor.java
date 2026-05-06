package xyz.zcraft.command.iface;

import xyz.zcraft.data.PendingMessage;

@FunctionalInterface
public interface ApiTaskExecutor {
    PendingMessage execute();
}
