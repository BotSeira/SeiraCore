package xyz.zcraft.command;

import xyz.zcraft.data.ApiTask;
import xyz.zcraft.data.PendingMessage;

public record RouteDecision(PendingMessage initialMessage, ApiTask apiTask) {
    static RouteDecision sync(PendingMessage message) {
        return new RouteDecision(message, null);
    }

    static RouteDecision async(PendingMessage message, ApiTask apiTask) {
        return new RouteDecision(message, apiTask);
    }
}
