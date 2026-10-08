package xyz.zcraft.seira.interaction;

import lombok.Getter;
import xyz.zcraft.seira.interaction.data.InteractionResponse;
import xyz.zcraft.seira.interaction.data.ListenerResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ListenerRegistry {
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();

    public void register(String identifier, InteractionListener listener) {
        listeners.put(identifier, new Listener(listener));
    }

    public void unregister(String identifier) {
        listeners.remove(identifier);
    }

    public InteractionResponse dispatch(String identifier, String operator) {
        Listener listener = listeners.get(identifier);
        if (listener == null) {
            return InteractionResponse.DUPLICATED;
        }

        synchronized (listener) {
            if (listener.isDisposed()) {
                return InteractionResponse.DUPLICATED;
            }

            final ListenerResult listenerResult = listener.getListener().onDispatch(operator);
            if (listenerResult.dispose()) {
                listener.dispose();
                listeners.remove(identifier);
            }

            return switch (listenerResult.response()) {
                case SUCCESS -> InteractionResponse.SUCCESS;
                case ADMIN_ONLY -> InteractionResponse.ADMIN_ONLY;
                case UNAUTHORIZED -> InteractionResponse.UNAUTHORIZED;
                default -> InteractionResponse.FAILED;
            };
        }
    }

    @Getter
    final static class Listener {
        private final InteractionListener listener;
        private boolean disposed;

        public Listener(InteractionListener listener) {
            this.listener = listener;
            this.disposed = false;
        }

        public void dispose() {
            this.disposed = true;
        }
    }
}
