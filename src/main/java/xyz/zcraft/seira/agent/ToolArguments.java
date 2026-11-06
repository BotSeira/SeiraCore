package xyz.zcraft.seira.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

final class ToolArguments {
    private static final Set<String> KEYWORDS = Set.of(
            "type", "description", "title", "properties", "required",
            "additionalProperties", "items", "enum",
            "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems"
    );

    static void checkSchema(JsonObject schema) {
        for (String key : schema.keySet()) {
            if (!KEYWORDS.contains(key)) {
                throw new IllegalArgumentException("Unsupported tool schema keyword: " + key);
            }
        }

        if (!schema.has("type")) {
            throw new IllegalArgumentException("Tool schema requires a type");
        }

        JsonElement types = schema.get("type");
        var allowed = Set.of("object", "array", "string", "boolean", "null", "integer", "number");
        if (types.isJsonArray()) {
            if (types.getAsJsonArray().isEmpty()) {
                throw new IllegalArgumentException("Empty schema type list");
            }
            for (JsonElement type : types.getAsJsonArray()) {
                if (!allowed.contains(type.getAsString())) {
                    throw new IllegalArgumentException("Unsupported schema type");
                }
            }
        } else if (!allowed.contains(types.getAsString())) {
            throw new IllegalArgumentException("Unsupported schema type");
        }

        if (schema.has("properties")) {
            schema.getAsJsonObject("properties")
                    .entrySet()
                    .forEach(e -> checkSchema(e.getValue().getAsJsonObject()));
        }

        if (schema.has("items")) {
            checkSchema(schema.getAsJsonObject("items"));
        }

        if (schema.has("additionalProperties")
                && (!schema.get("additionalProperties").isJsonPrimitive() || !schema.getAsJsonPrimitive("additionalProperties").isBoolean())) {
            throw new IllegalArgumentException("additionalProperties must be boolean");
        }

        if (schema.has("required")) {
            for (JsonElement name : schema.getAsJsonArray("required")) {
                if (!schema.has("properties")
                        || !schema.getAsJsonObject("properties").has(name.getAsString())) {
                    throw new IllegalArgumentException("Required property is not defined");
                }
            }
        }
    }

    static void validate(JsonObject schema, JsonElement value) {
        JsonElement type = schema.get("type");
        boolean matches = type.isJsonArray()
                ? type.getAsJsonArray().asList().stream().anyMatch(t -> matches(t.getAsString(), value))
                : matches(type.getAsString(), value);

        if (!matches) {
            throw new IllegalArgumentException("Argument type mismatch");
        }

        if (schema.has("enum")
                && !schema.getAsJsonArray("enum").contains(value)) {
            throw new IllegalArgumentException("Invalid enum value");
        }

        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonObject properties = schema.has("properties")
                    ? schema.getAsJsonObject("properties")
                    : new JsonObject();

            if (schema.has("required")) {
                for (var name : schema.getAsJsonArray("required")) {
                    if (!object.has(name.getAsString())) {
                        throw new IllegalArgumentException("Missing argument");
                    }
                }
            }

            for (var entry : object.entrySet()) {
                if (properties.has(entry.getKey())) {
                    validate(properties.getAsJsonObject(entry.getKey()), entry.getValue());
                } else if (schema.has("additionalProperties") && !schema.get("additionalProperties").getAsBoolean()) {
                    throw new IllegalArgumentException("Unknown argument");
                }
            }
        } else if (value.isJsonArray()) {
            bounds(schema, value.getAsJsonArray().size(), "minItems", "maxItems");
            if (schema.has("items")) {
                value.getAsJsonArray().forEach(item -> validate(schema.getAsJsonObject("items"), item));
            }
        } else if (value.isJsonPrimitive()) {
            var primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) {
                bounds(schema, primitive.getAsString().codePointCount(0, primitive.getAsString().length()), "minLength", "maxLength");
            }
            if (primitive.isNumber()) {
                bounds(schema, primitive.getAsDouble(), "minimum", "maximum");
            }
        }
    }

    private static void bounds(JsonObject schema, double value, String min, String max) {
        if (!Double.isFinite(value) || (schema.has(min) && (value < schema.get(min).getAsDouble()))
                || (schema.has(max) && (value > schema.get(max).getAsDouble()))) {
            throw new IllegalArgumentException("Argument outside bounds");
        }
    }

    private static boolean matches(String type, JsonElement value) {
        return switch (type) {
            case "null" -> value.isJsonNull();
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber()
                    && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
            default -> throw new IllegalArgumentException("Unsupported schema type");
        };
    }
}
