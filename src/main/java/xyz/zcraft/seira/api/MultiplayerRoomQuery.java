package xyz.zcraft.seira.api;

final class MultiplayerRoomQuery {
    private MultiplayerRoomQuery() {
    }

    static String query(String roomId) {
        if (roomId == null) return "";
        try {
            if (roomId.matches("[0-9]+") && Long.parseLong(roomId) > 0)
                return "&room=" + Long.parseLong(roomId);
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Invalid multiplayer room ID");
    }

}
