package xyz.zcraft.seira.util;

import org.snakeyaml.engine.v2.common.UriEncoder;

import java.util.HashMap;

public class Query {
    private final HashMap<String, String> params = new HashMap<>();

    public static Query create() {
        return new Query();
    }

    public Query param(String key, String value) {
        params.put(key, UriEncoder.encode(value));
        return this;
    }

    public Query param(String key, Object value) {
        params.put(key, UriEncoder.encode(value.toString()));
        return this;
    }

    public String build() {
        StringBuilder builder = new StringBuilder("?");
        params.forEach((key, value) -> builder.append(key).append("=").append(value).append("&"));
        builder.deleteCharAt(builder.length() - 1);
        return builder.toString();
    }
}
