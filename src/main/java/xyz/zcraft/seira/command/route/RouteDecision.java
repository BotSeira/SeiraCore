package xyz.zcraft.seira.command.route;

import xyz.zcraft.seira.api.data.ApiTask;
import xyz.zcraft.seira.bot.data.PendingMessage;

import java.util.function.Consumer;

public record RouteDecision(
        PendingMessage initialMessage,
        ApiTask apiTask,
        boolean enqueueMessage,
        Consumer<Boolean> onSent) {
    public static RouteDecision sync(PendingMessage message) {
        return new RouteDecision(message, null, false, null);
    }

    public static RouteDecision sync(PendingMessage message, Consumer<Boolean> onSent) {
        return new RouteDecision(message, null, false, onSent);
    }

    public static RouteDecision async(PendingMessage message, ApiTask apiTask) {
        return new RouteDecision(message, apiTask, true, null);
    }
}
