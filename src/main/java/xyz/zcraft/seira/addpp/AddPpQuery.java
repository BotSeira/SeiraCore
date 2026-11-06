package xyz.zcraft.seira.addpp;

import java.util.List;

public record AddPpQuery(Double pp, Integer count, Long beatmapId, List<String> conditions) {
    public AddPpQuery {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
}
