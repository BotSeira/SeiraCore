package xyz.zcraft.seira.command;

import java.util.*;

public final class CommandRegistry {
    private final Map<String, CommandHandler> handlers;

    private CommandRegistry(Map<String, CommandHandler> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String normalize(String command) {
        return Objects.requireNonNull(command, "command").trim().toLowerCase(Locale.ROOT);
    }

    public void dispatch(Context context, CommandHandler fallback) {
        CommandHandler handler = handlers.get(normalize(context.command()));
        (handler == null ? fallback : handler).handle(context);
    }

    public Set<String> registeredCommands() {
        return handlers.keySet();
    }

    public static final class Builder {
        private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();

        public Builder register(CommandHandler handler, String... commands) {
            return registerInternal(handler, commands);
        }

        private Builder registerInternal(CommandHandler handler, String... commands) {
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
}
