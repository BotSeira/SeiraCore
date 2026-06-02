package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class FileInfo {
    @SerializedName("file_uuid")
    private String fileUuid;
    @SerializedName("file_info")
    private String fileInfo;
    private Integer ttl;
    private String string;
}
