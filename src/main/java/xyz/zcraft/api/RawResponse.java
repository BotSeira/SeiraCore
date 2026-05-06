package xyz.zcraft.api;

import com.google.gson.JsonElement;
import lombok.Data;

@Data
public class RawResponse {
    private boolean success;
    private String message;
    private JsonElement data;
}
