package xyz.zcraft.seira.command.iface;

@FunctionalInterface
public interface ApiTaskFinalizer {
    void execute(boolean result);
}

