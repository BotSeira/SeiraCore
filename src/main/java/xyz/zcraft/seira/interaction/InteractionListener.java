package xyz.zcraft.seira.interaction;

import xyz.zcraft.seira.interaction.data.ListenerResult;

@FunctionalInterface
public interface InteractionListener {
    ListenerResult onDispatch(String operator);
}
