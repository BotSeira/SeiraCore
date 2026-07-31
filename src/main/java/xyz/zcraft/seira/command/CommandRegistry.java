package xyz.zcraft.seira.command;

import xyz.zcraft.seira.command.route.RouteDecision;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public final class CommandRegistry {
    private final Map<String, Function<Context, RouteDecision>> handlers;

    private CommandRegistry(Map<String, Function<Context, RouteDecision>> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    public static Builder builder() {
        return new Builder();
    }

    public RouteDecision dispatch(Context context, Supplier<RouteDecision> fallback) {
        Function<Context, RouteDecision> handler = handlers.get(normalize(context.command()));
        return handler == null ? fallback.get() : handler.apply(context);
    }

    public Set<String> registeredCommands() {
        return handlers.keySet();
    }

    public static final class Builder {
        private final Map<String, Function<Context, RouteDecision>> handlers = new LinkedHashMap<>();

        public Builder register(Function<Context, RouteDecision> handler, String... commands) {
            Objects.requireNonNull(handler, "handler");
            if (commands == null || commands.length == 0) {
                throw new IllegalArgumentException("At least one command is required");
            }

            for (String command : commands) {
                String normalized = normalize(command);
                if (normalized.isEmpty()) {
                    throw new IllegalArgumentException("Command must not be blank");
                }
                if (handlers.putIfAbsent(normalized, handler) != null) {
                    throw new IllegalArgumentException("Duplicate command registration: " + normalized);
                }
            }
            return this;
        }

        public CommandRegistry build() {
            return new CommandRegistry(handlers);
        }
    }

    private static String normalize(String command) {
        return Objects.requireNonNull(command, "command").trim().toLowerCase(Locale.ROOT);
    }
}
