package xyz.zcraft.seira.command;

import xyz.zcraft.seira.data.ApiTask;
import xyz.zcraft.seira.data.PendingMessage;

import java.util.function.Consumer;

public record RouteDecision(PendingMessage initialMessage, ApiTask apiTask, boolean enqueueMessage, Consumer<Boolean> onSent) {
    static RouteDecision sync(PendingMessage message) {
        return new RouteDecision(message, null, false, null);
    }

    static RouteDecision sync(PendingMessage message, Consumer<Boolean> onSent) {
        return new RouteDecision(message, null, false, onSent);
    }

    static RouteDecision async(PendingMessage message, ApiTask apiTask) {
        return new RouteDecision(message, apiTask, true, null);
    }
}
