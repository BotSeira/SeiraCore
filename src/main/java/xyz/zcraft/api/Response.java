package xyz.zcraft.api;

import lombok.Builder;
import lombok.Data;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Optional;

@Data
@Builder
public class Response<T> {
    private T content;

    private String userId;
    private List<String> userIds;

    private String beatmapId;
    private List<String> beatmapIds;
    private List<String> beatmapStars;

    private String beatmapsetId;
    private List<String> beatmapsetIds;

    private String scoreId;
    private List<String> scoreIds;

    private String roomId;

    public static <T> Response.ResponseBuilder<T> fromHeaders(HttpHeaders headers) {
        return Response.<T>builder()
                .userId(headers.firstValue("X-User-Id").orElse(null))
                .userIds(headers.firstValue("X-User-Ids").flatMap(Response::parseCsvHeader).orElse(null))
                .beatmapId(headers.firstValue("X-Beatmap-Id").orElse(null))
                .beatmapIds(headers.firstValue("X-Beatmap-Ids").flatMap(Response::parseCsvHeader).orElse(null))
                .beatmapStars(headers.firstValue("X-Beatmap-Stars").flatMap(Response::parseCsvHeader).orElse(null))
                .beatmapsetId(headers.firstValue("X-Beatmapset-Id").orElse(null))
                .beatmapsetIds(headers.firstValue("X-Beatmapset-Ids").flatMap(Response::parseCsvHeader).orElse(null))
                .scoreId(headers.firstValue("X-Score-Id").orElse(null))
                .scoreIds(headers.firstValue("X-Score-Ids").flatMap(Response::parseCsvHeader).orElse(null))
                .roomId(headers.firstValue("X-Room-Id").orElse(null));
    }

    private static Optional<List<String>> parseCsvHeader(String headerValue) {
        if (headerValue == null) return Optional.empty();
        return Optional.of(List.of(headerValue.split(",")));
    }
}
