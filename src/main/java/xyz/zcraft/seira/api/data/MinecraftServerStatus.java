package xyz.zcraft.seira.api.data;

import com.google.gson.JsonElement;

import java.util.List;

public record MinecraftServerStatus(
        String host,
        Integer port,
        Long latency,
        Status status
) {
    public record Status(
            Version version,
            Players players,
            String description,
            JsonElement descriptionRaw,
            String favicon
    ){}

    public record Version(
            String name,
            Integer protocol
    ) {
    }

    public record Players(
            Long max,
            Long online,
            List<Sample> samples
    ) {
        public record Sample(
                String id,
                String name
        ) {
        }
    }
}
