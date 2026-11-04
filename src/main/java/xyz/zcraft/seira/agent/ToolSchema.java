package xyz.zcraft.seira.agent;

import com.google.gson.*;

import java.util.Objects;

/**
 * Fluent schema construction. Nested schemas and exported JSON are copied, not shared.
 */
public final class ToolSchema {
    private final JsonObject value = new JsonObject();

    private ToolSchema(String type) {
        value.addProperty("type", type);
    }

    public static ToolSchema object() {
        return new ToolSchema("object");
    }

    public static ToolSchema string() {
        return new ToolSchema("string");
    }

    public static ToolSchema integer() {
        return new ToolSchema("integer");
    }

    public static ToolSchema number() {
        return new ToolSchema("number");
    }

    public static ToolSchema bool() {
        return new ToolSchema("boolean");
    }

    public static ToolSchema nil() {
        return new ToolSchema("null");
    }

    public static ToolSchema array(ToolSchema items) {
        ToolSchema schema = new ToolSchema("array");
        schema.value.add("items", items.toJson());
        return schema;
    }

    public ToolSchema property(String name, ToolSchema schema) {
        JsonElement type = value.get("type");
        boolean object = type.isJsonArray() ? type.getAsJsonArray().contains(new JsonPrimitive("object"))
                : "object".equals(type.getAsString());
        if (!object) throw new IllegalStateException("Properties require an object schema");
        if (!value.has("properties")) value.add("properties", new JsonObject());
        JsonObject properties = value.getAsJsonObject("properties");
        if (properties.has(name)) throw new IllegalArgumentException("Duplicate property: " + name);
        properties.add(Objects.requireNonNull(name), schema.toJson());
        return this;
    }

    public ToolSchema required(String... names) {
        JsonArray required = new JsonArray();
        for (String name : names) {
            if (!value.has("properties") || !value.getAsJsonObject("properties").has(name))
                throw new IllegalArgumentException("Undefined required property: " + name);
            if (!required.contains(new JsonPrimitive(name))) required.add(name);
        }
        value.add("required", required);
        return this;
    }

    public ToolSchema additionalProperties(boolean allowed) {
        value.addProperty("additionalProperties", allowed);
        return this;
    }

    public ToolSchema description(String text) {
        value.addProperty("description", Objects.requireNonNull(text));
        return this;
    }

    public ToolSchema title(String text) {
        value.addProperty("title", Objects.requireNonNull(text));
        return this;
    }

    public ToolSchema minimum(Number bound) {
        value.addProperty("minimum", bound);
        return this;
    }

    public ToolSchema maximum(Number bound) {
        value.addProperty("maximum", bound);
        return this;
    }

    public ToolSchema minLength(int bound) {
        return limit("minLength", bound);
    }

    public ToolSchema maxLength(int bound) {
        return limit("maxLength", bound);
    }

    public ToolSchema minItems(int bound) {
        return limit("minItems", bound);
    }

    public ToolSchema maxItems(int bound) {
        return limit("maxItems", bound);
    }

    private ToolSchema limit(String name, int bound) {
        if (bound < 0) throw new IllegalArgumentException("Negative schema limit");
        value.addProperty(name, bound);
        return this;
    }

    public ToolSchema nullable() {
        JsonArray types = value.get("type").isJsonArray() ? value.getAsJsonArray("type") : new JsonArray();
        if (types.isEmpty()) types.add(value.get("type"));
        if (!types.contains(new JsonPrimitive("null"))) types.add("null");
        value.add("type", types);
        return this;
    }

    public ToolSchema enumValues(Object... values) {
        JsonArray choices = new JsonArray();
        for (Object item : values) {
            if (item == null) choices.add(JsonNull.INSTANCE);
            else if (item instanceof String text) choices.add(text);
            else if (item instanceof Number number) choices.add(number);
            else if (item instanceof Boolean bool) choices.add(bool);
            else throw new IllegalArgumentException("Enum values must be scalar");
        }
        if (choices.isEmpty()) throw new IllegalArgumentException("Empty enum");
        value.add("enum", choices);
        return this;
    }

    public JsonObject toJson() {
        ToolArguments.checkSchema(value);
        return value.deepCopy();
    }
}
