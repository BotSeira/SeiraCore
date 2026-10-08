package xyz.zcraft.seira.command;

import xyz.zcraft.seira.bot.QQApi;
import xyz.zcraft.seira.bot.data.Button;
import xyz.zcraft.seira.interaction.InteractionListener;
import xyz.zcraft.seira.interaction.ListenerRegistry;
import xyz.zcraft.seira.interaction.data.InteractionEvent;
import xyz.zcraft.seira.interaction.data.InteractionResponse;
import xyz.zcraft.seira.util.TokenManager;

import java.util.UUID;

public class InteractionHandler {
    private final ListenerRegistry listenerRegistry = new ListenerRegistry();
    private final TokenManager tokenManager;

    public InteractionHandler(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public void onInteraction(InteractionEvent event) {
        if (event.type() != 11) return;

        final String userId = event.resolveUserId();
        final String identifier = event.data().resolved().buttonData();

        final InteractionResponse response = listenerRegistry.dispatch(identifier, userId);

        QQApi.putInteractionResponse(tokenManager.getToken(), event.id(), response);
    }

    public Button createButton(int id, String label, InteractionListener listener) {
        final String identifier = id + "#" + UUID.randomUUID();
        listenerRegistry.register(identifier, listener);
        return Button.interaction(id, identifier, label);
    }
}
