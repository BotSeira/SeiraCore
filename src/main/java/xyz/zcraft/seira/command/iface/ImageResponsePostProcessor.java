package xyz.zcraft.seira.command.iface;

import xyz.zcraft.seira.api.Response;
import xyz.zcraft.seira.data.PendingMessage;

@FunctionalInterface
public interface ImageResponsePostProcessor {
    PendingMessage execute(Response<?> response);
}
