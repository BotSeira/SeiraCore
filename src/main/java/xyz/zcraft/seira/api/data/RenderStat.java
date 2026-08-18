package xyz.zcraft.seira.api.data;

import lombok.Data;

@Data
public class RenderStat {
    private String id;
    private String status;
    private String progress;
    private String speed;
    private String eta;
    private String error;
}
