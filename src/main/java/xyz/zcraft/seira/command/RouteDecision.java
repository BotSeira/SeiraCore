package xyz.zcraft.seira.command;

import xyz.zcraft.seira.data.ApiTask;
import xyz.zcraft.seira.data.PendingMessage;

public record RouteDecision(PendingMessage initialMessage, ApiTask apiTask) {
    static RouteDecision sync(PendingMessage message) {
        return new RouteDecision(message, null);
    }

    static RouteDecision async(PendingMessage message, ApiTask apiTask) {
        return new RouteDecision(message, apiTask);
    }
}
