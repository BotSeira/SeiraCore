package xyz.zcraft.seira.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class ConversationStore {
    private static final Gson GSON = new Gson();
    private final Path directory;

    public ConversationStore(Path directory) {
        this.directory = directory;
    }

    private Path file(String id) {
        return directory.resolve(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    public Saved load(String id) throws IOException {
        Path path = file(id);
        if (!Files.exists(path)) return new Saved(null, Map.of(), List.of());

        JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!id.equals(root.get("conversationId").getAsString()))
            throw new IOException("Conversation identity mismatch");

        // Remote stored responses have a finite lifetime. Begin a fresh chain after a day of inactivity.
        boolean expired = System.currentTimeMillis() - root.get("updatedAt").getAsLong() > 86_400_000L;

        Map<String, String> participants = new LinkedHashMap<>();
        root.getAsJsonObject("participants").entrySet().forEach(e -> participants.put(e.getKey(), e.getValue().getAsString()));
        var history = new ArrayList<AgentInput.Text>();
        if (root.has("history")) {
            root.getAsJsonArray("history").forEach(item -> history.add(GSON.fromJson(item, AgentInput.Text.class)));
        }
        return new Saved(expired ? null : root.get("responseId").getAsString(), participants, List.copyOf(history));
    }

    public void save(String id, String responseId, Map<String, String> participants) throws IOException {
        save(id, responseId, participants, List.of());
    }

    public void save(String id, String responseId, Map<String, String> participants,
                     Collection<AgentInput.History> history) throws IOException {
        Files.createDirectories(directory);

        JsonObject root = new JsonObject();
        root.addProperty("conversationId", id);
        root.addProperty("responseId", responseId);
        root.addProperty("updatedAt", System.currentTimeMillis());
        root.add("participants", GSON.toJsonTree(participants));
        var items = new JsonArray();
        history.forEach(item -> items.add(AgentInput.toJson(item)));
        root.add("history", items);

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

    public void clearAll() throws IOException {
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> f.endsWith(".json"))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    public record Saved(String responseId, Map<String, String> participants, List<AgentInput.Text> history) {
    }
}
