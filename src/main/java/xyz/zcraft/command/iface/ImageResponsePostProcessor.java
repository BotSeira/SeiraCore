package xyz.zcraft.command.iface;

import xyz.zcraft.api.Response;
import xyz.zcraft.data.PendingMessage;

@FunctionalInterface
public interface ImageResponsePostProcessor {
    PendingMessage execute(Response<?> response);
}
