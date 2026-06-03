package xyz.zcraft.seira.api.data;

import com.google.gson.JsonElement;
import lombok.Data;

@Data
public class RawResponse {
    private boolean success;
    private String message;
    private JsonElement data;
}
