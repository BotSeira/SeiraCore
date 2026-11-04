package xyz.zcraft.seira.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ConversationStore {
    private final Path directory;

    public ConversationStore(Path directory) {
        this.directory = directory;
    }

    private Path file(String id) {
        return directory.resolve(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    public Saved load(String id) throws IOException {
        Path path = file(id);
        if (!Files.exists(path)) return new Saved(null, Map.of());

        JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!id.equals(root.get("conversationId").getAsString()))
            throw new IOException("Conversation identity mismatch");

        // Remote stored responses have a finite lifetime. Begin a fresh chain after a day of inactivity.
        if (System.currentTimeMillis() - root.get("updatedAt").getAsLong() > 86_400_000L)
            return new Saved(null, Map.of());

        Map<String, String> participants = new LinkedHashMap<>();
        root.getAsJsonObject("participants").entrySet().forEach(e -> participants.put(e.getKey(), e.getValue().getAsString()));
        return new Saved(root.get("responseId").getAsString(), participants);
    }

    public void save(String id, String responseId, Map<String, String> participants) throws IOException {
        Files.createDirectories(directory);

        JsonObject root = new JsonObject();
        root.addProperty("conversationId", id);
        root.addProperty("responseId", responseId);
        root.addProperty("updatedAt", System.currentTimeMillis());
        root.add("participants", new Gson().toJsonTree(participants));

        Path destination = file(id);
        Path temporary = Files.createTempFile(directory, "conversation-", ".tmp");

        try {
            Files.writeString(temporary, root.toString());

            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void clear(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    public record Saved(String responseId, Map<String, String> participants) {
    }
}
