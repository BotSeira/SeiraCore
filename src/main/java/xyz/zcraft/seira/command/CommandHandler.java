package xyz.zcraft.seira.command;

@FunctionalInterface
public interface CommandHandler {
    void handle(Context context);
}
