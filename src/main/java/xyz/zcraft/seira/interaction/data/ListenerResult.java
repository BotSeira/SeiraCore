package xyz.zcraft.seira.interaction.data;

public record ListenerResult(
        ListenerResponse response,
        boolean dispose
) {
    public static ListenerResult of(ListenerResponse response, boolean dispose) {
        return new ListenerResult(response, dispose);
    }

    public static ListenerResult ofDispose(ListenerResponse response) {
        return new ListenerResult(response, true);
    }
}
